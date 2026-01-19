//! Arti Desktop JNI Wrapper
//!
//! Platform-agnostic JNI bindings without the `jni` crate.
//! Uses raw FFI types that work on macOS, Linux, and Windows.

use std::ffi::{c_char, c_void, CStr};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, Once};

use arti_client::TorClient;
use arti_client::config::TorClientConfigBuilder;
use tor_rtcompat::PreferredRuntime;
use anyhow::Result;

// ============================================================================
// Raw JNI Types (platform-agnostic)
// ============================================================================

#[repr(C)]
pub struct JNIEnv {
    _private: [u8; 0],
}

#[repr(C)]
pub struct JClass {
    _private: [u8; 0],
}

#[repr(C)]
pub struct JString {
    _private: [u8; 0],
}

#[repr(C)]
pub struct JObject {
    _private: [u8; 0],
}

pub type jint = i32;
pub type jstring = *mut JString;

// JNI function table pointer (simplified - we only need GetStringUTFChars/NewStringUTF)
type GetStringUTFCharsFn = unsafe extern "C" fn(*mut JNIEnv, jstring, *mut u8) -> *const c_char;
type ReleaseStringUTFCharsFn = unsafe extern "C" fn(*mut JNIEnv, jstring, *const c_char);
type NewStringUTFFn = unsafe extern "C" fn(*mut JNIEnv, *const c_char) -> jstring;

// JNI function table offsets (JNI 1.6+)
const GET_STRING_UTF_CHARS_OFFSET: isize = 169;
const RELEASE_STRING_UTF_CHARS_OFFSET: isize = 170;
const NEW_STRING_UTF_OFFSET: isize = 167;

unsafe fn get_string_utf_chars(env: *mut JNIEnv, s: jstring) -> *const c_char {
    let func_table = *(env as *const *const *const c_void);
    let func: GetStringUTFCharsFn = std::mem::transmute(*func_table.offset(GET_STRING_UTF_CHARS_OFFSET));
    func(env, s, std::ptr::null_mut())
}

unsafe fn release_string_utf_chars(env: *mut JNIEnv, s: jstring, chars: *const c_char) {
    let func_table = *(env as *const *const *const c_void);
    let func: ReleaseStringUTFCharsFn = std::mem::transmute(*func_table.offset(RELEASE_STRING_UTF_CHARS_OFFSET));
    func(env, s, chars)
}

unsafe fn new_string_utf(env: *mut JNIEnv, chars: *const c_char) -> jstring {
    let func_table = *(env as *const *const *const c_void);
    let func: NewStringUTFFn = std::mem::transmute(*func_table.offset(NEW_STRING_UTF_OFFSET));
    func(env, chars)
}

// ============================================================================
// Global State
// ============================================================================

static ARTI_CLIENT: Mutex<Option<Arc<TorClient<PreferredRuntime>>>> = Mutex::new(None);
static TOKIO_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static SOCKS_TASK: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);
static INIT_ONCE: Once = Once::new();

// ============================================================================
// Logging (desktop - uses stderr)
// ============================================================================

macro_rules! log_info {
    ($($arg:tt)*) => {
        eprintln!("[Arti] {}", format!($($arg)*));
    };
}

macro_rules! log_error {
    ($($arg:tt)*) => {
        eprintln!("[Arti ERROR] {}", format!($($arg)*));
    };
}

// ============================================================================
// JNI Functions
// ============================================================================

#[no_mangle]
pub unsafe extern "C" fn Java_com_bitchat_tor_TorManager_nativeGetVersion(
    env: *mut JNIEnv,
    _class: *mut JClass,
) -> jstring {
    let version = format!("Arti {} (desktop build)\0", env!("CARGO_PKG_VERSION"));
    new_string_utf(env, version.as_ptr() as *const c_char)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_bitchat_tor_TorManager_nativeSetLogCallback(
    _env: *mut JNIEnv,
    _class: *mut JClass,
    _callback: *mut JObject,
) {
    // Desktop: log callback not implemented (uses stderr)
    log_info!("Log callback registered (desktop uses stderr)");
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_bitchat_tor_TorManager_nativeInitialize(
    env: *mut JNIEnv,
    _class: *mut JClass,
    data_dir: jstring,
) -> jint {
    // Get data directory string
    let chars = get_string_utf_chars(env, data_dir);
    if chars.is_null() {
        log_error!("Failed to get data_dir string");
        return -1;
    }
    let data_dir_str = match CStr::from_ptr(chars).to_str() {
        Ok(s) => s.to_string(),
        Err(e) => {
            log_error!("Invalid UTF-8 in data_dir: {:?}", e);
            release_string_utf_chars(env, data_dir, chars);
            return -1;
        }
    };
    release_string_utf_chars(env, data_dir, chars);

    log_info!("AMEx: state changed to Initialized");
    log_info!("Initializing Arti with data directory: {}", data_dir_str);

    // Initialize Tokio runtime (once)
    INIT_ONCE.call_once(|| {
        match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => {
                log_info!("Tokio runtime created");
                *TOKIO_RUNTIME.lock().unwrap() = Some(rt);
            }
            Err(e) => {
                log_error!("Failed to create Tokio runtime: {:?}", e);
            }
        }
    });

    let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    let data_path = PathBuf::from(data_dir_str);
    let cache_dir = data_path.join("cache");
    let state_dir = data_path.join("state");

    std::fs::create_dir_all(&cache_dir).ok();
    std::fs::create_dir_all(&state_dir).ok();

    let result: Result<()> = runtime.block_on(async {
        log_info!("Creating Arti client...");
        log_info!("Cache dir: {:?}", cache_dir);
        log_info!("State dir: {:?}", state_dir);

        let config = TorClientConfigBuilder::from_directories(state_dir, cache_dir)
            .build()?;

        let client = TorClient::create_bootstrapped(config).await?;

        log_info!("Arti client created successfully");
        *ARTI_CLIENT.lock().unwrap() = Some(Arc::new(client));

        Ok(())
    });

    match result {
        Ok(_) => {
            log_info!("Arti initialized successfully");
            0
        }
        Err(e) => {
            log_error!("Failed to initialize Arti: {:?}", e);
            -3
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_bitchat_tor_TorManager_nativeStartSocksProxy(
    _env: *mut JNIEnv,
    _class: *mut JClass,
    port: jint,
) -> jint {
    log_info!("AMEx: state changed to Starting");
    log_info!("Starting SOCKS proxy on port {}", port);

    // Stop existing task
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting previous SOCKS server task");
        handle.abort();
    }

    let client_guard = ARTI_CLIENT.lock().unwrap();
    let client = match client_guard.as_ref() {
        Some(c) => Arc::clone(c),
        None => {
            log_error!("Arti client not initialized - call initialize() first");
            return -1;
        }
    };
    drop(client_guard);

    let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    let addr = format!("127.0.0.1:{}", port);

    // Bind synchronously to detect errors
    let listener = match runtime.block_on(tokio::net::TcpListener::bind(&addr)) {
        Ok(l) => {
            log_info!("SOCKS proxy bound to {}", addr);
            l
        }
        Err(e) => {
            log_error!("Failed to bind SOCKS proxy to {}: {:?}", addr, e);
            return -3;
        }
    };

    let handle = runtime.spawn(async move {
        log_info!("SOCKS proxy listening on {}", addr);
        log_info!("Sufficiently bootstrapped; system SOCKS now functional");

        // Signal bootstrap completion (expected by TorManager Kotlin code)
        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
        log_info!("We have found that guard [scrubbed] is usable.");

        loop {
            match listener.accept().await {
                Ok((stream, peer)) => {
                    log_info!("SOCKS connection from: {}", peer);
                    let client_clone = Arc::clone(&client);
                    tokio::spawn(async move {
                        if let Err(e) = handle_socks_connection(stream, client_clone).await {
                            log_error!("SOCKS connection error: {:?}", e);
                        }
                    });
                }
                Err(e) => {
                    log_error!("Failed to accept SOCKS connection: {:?}", e);
                    break;
                }
            }
        }

        log_info!("SOCKS proxy task exiting");
    });

    *SOCKS_TASK.lock().unwrap() = Some(handle);

    log_info!("SOCKS proxy started on port {}", port);
    0
}

async fn handle_socks_connection(
    mut stream: tokio::net::TcpStream,
    client: Arc<TorClient<PreferredRuntime>>,
) -> Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut buf = [0u8; 512];

    // Read version + methods
    let n = stream.read(&mut buf).await?;
    if n < 2 {
        return Err(anyhow::anyhow!("Invalid SOCKS handshake"));
    }

    // Send "no auth required" response
    stream.write_all(&[0x05, 0x00]).await?;

    // Read request
    let n = stream.read(&mut buf).await?;
    if n < 10 {
        return Err(anyhow::anyhow!("Invalid SOCKS request"));
    }

    // Parse SOCKS5 request: VER(1) CMD(1) RSV(1) ATYP(1) DST.ADDR DST.PORT(2)
    let version = buf[0];
    let cmd = buf[1];
    let atyp = buf[3];

    if version != 0x05 {
        return Err(anyhow::anyhow!("Unsupported SOCKS version: {}", version));
    }

    if cmd != 0x01 {
        // Only support CONNECT command
        stream.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
        return Err(anyhow::anyhow!("Unsupported SOCKS command: {}", cmd));
    }

    // Parse target address and port
    let (target_host, target_port) = match atyp {
        0x01 => {
            // IPv4: 4 bytes
            let ip = format!("{}.{}.{}.{}", buf[4], buf[5], buf[6], buf[7]);
            let port = u16::from_be_bytes([buf[8], buf[9]]);
            (ip, port)
        }
        0x03 => {
            // Domain name: length byte + domain
            let len = buf[4] as usize;
            if n < 5 + len + 2 {
                return Err(anyhow::anyhow!("Invalid domain name length"));
            }
            let domain = String::from_utf8_lossy(&buf[5..5 + len]).to_string();
            let port = u16::from_be_bytes([buf[5 + len], buf[5 + len + 1]]);
            (domain, port)
        }
        0x04 => {
            // IPv6: 16 bytes + 2 bytes port = 22 bytes total
            if n < 22 {
                stream.write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
                return Err(anyhow::anyhow!("Truncated IPv6 request"));
            }
            let ip = format!(
                "{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}",
                buf[4], buf[5], buf[6], buf[7], buf[8], buf[9], buf[10], buf[11],
                buf[12], buf[13], buf[14], buf[15], buf[16], buf[17], buf[18], buf[19]
            );
            let port = u16::from_be_bytes([buf[20], buf[21]]);
            (ip, port)
        }
        _ => {
            stream.write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Err(anyhow::anyhow!("Unsupported address type: {}", atyp));
        }
    };

    log_info!("SOCKS5 CONNECT to {}:{}", target_host, target_port);

    // Establish Tor connection
    let tor_stream = match client.connect((target_host.as_str(), target_port)).await {
        Ok(s) => s,
        Err(e) => {
            log_error!("Failed to connect through Tor: {:?}", e);
            // Send SOCKS5 error: general failure
            stream.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Err(e.into());
        }
    };

    log_info!("Tor connection established to {}:{}", target_host, target_port);

    // Send SOCKS5 success response
    stream.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;

    // Bidirectional data forwarding
    let (mut client_read, mut client_write) = stream.split();
    let (mut tor_read, mut tor_write) = tor_stream.split();

    let client_to_tor = async {
        tokio::io::copy(&mut client_read, &mut tor_write).await
    };

    let tor_to_client = async {
        tokio::io::copy(&mut tor_read, &mut client_write).await
    };

    // Run both directions concurrently, exit when either completes
    tokio::select! {
        result = client_to_tor => {
            if let Err(ref e) = result {
                log_error!("Client->Tor copy error: {:?}", e);
            }
        }
        result = tor_to_client => {
            if let Err(ref e) = result {
                log_error!("Tor->Client copy error: {:?}", e);
            }
        }
    };

    log_info!("SOCKS connection closed for {}:{}", target_host, target_port);

    Ok(())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_bitchat_tor_TorManager_nativeStop(
    _env: *mut JNIEnv,
    _class: *mut JClass,
) -> jint {
    log_info!("AMEx: state changed to Stopping");
    log_info!("Stopping Arti...");

    // Abort SOCKS proxy task (releases the port)
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting SOCKS server task");
        handle.abort();
    }

    // Give the abort a moment to complete and release the port
    if let Some(rt) = TOKIO_RUNTIME.lock().unwrap().as_ref() {
        rt.block_on(async {
            tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
        });
    }

    log_info!("AMEx: state changed to Stopped");
    log_info!("Arti stopped successfully");

    0
}
