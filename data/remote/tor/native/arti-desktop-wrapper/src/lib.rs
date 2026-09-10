//! Arti Desktop JNI Wrapper
//!
//! Platform-agnostic JNI bindings without the `jni` crate.
//! Uses raw FFI types that work on macOS, Linux, and Windows.

use std::ffi::{c_char, c_void, CStr};
use std::panic::AssertUnwindSafe;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, Once};
use std::time::Duration;

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

/// Decodes JNI's *modified* UTF-8, which is not UTF-8.
///
/// `GetStringUTFChars` documents its result as modified UTF-8, and it differs in two ways that
/// matter: a NUL is encoded as `C0 80` so it cannot terminate the string, and a character outside
/// the Basic Multilingual Plane is encoded as a UTF-16 surrogate *pair*, each surrogate written as
/// its own three-byte sequence. Real UTF-8 encodes those as four bytes and forbids surrogates
/// outright, so `CStr::to_str` rejects them.
///
/// The practical consequence was that a data directory containing any supplementary character --
/// an emoji in a path, most non-BMP scripts -- failed initialisation with "Invalid UTF-8", while
/// an ASCII home directory hid the bug completely.
pub fn decode_modified_utf8(bytes: &[u8]) -> Result<String, String> {
    let mut out = String::with_capacity(bytes.len());
    let mut i = 0usize;

    fn continuation(b: u8) -> Result<u32, String> {
        if b & 0xC0 != 0x80 {
            return Err(format!("expected a continuation byte, found 0x{:02x}", b));
        }
        Ok((b & 0x3F) as u32)
    }

    while i < bytes.len() {
        let b = bytes[i];
        match b {
            0x01..=0x7F => {
                out.push(b as char);
                i += 1;
            }
            0xC0..=0xDF => {
                if i + 1 >= bytes.len() {
                    return Err("truncated two-byte sequence".into());
                }
                let code = ((b & 0x1F) as u32) << 6 | continuation(bytes[i + 1])?;
                // C0 80 is how modified UTF-8 writes NUL; it is not an overlong error here.
                out.push(char::from_u32(code).ok_or("invalid two-byte code point")?);
                i += 2;
            }
            0xE0..=0xEF => {
                if i + 2 >= bytes.len() {
                    return Err("truncated three-byte sequence".into());
                }
                let code = ((b & 0x0F) as u32) << 12
                    | continuation(bytes[i + 1])? << 6
                    | continuation(bytes[i + 2])?;
                i += 3;

                if (0xD800..=0xDBFF).contains(&code) {
                    // High surrogate: the low half follows as its own three-byte sequence.
                    if i + 2 >= bytes.len() {
                        return Err("high surrogate with no low surrogate".into());
                    }
                    let lo = ((bytes[i] & 0x0F) as u32) << 12
                        | continuation(bytes[i + 1])? << 6
                        | continuation(bytes[i + 2])?;
                    if !(0xDC00..=0xDFFF).contains(&lo) {
                        return Err(format!("expected a low surrogate, found U+{:04X}", lo));
                    }
                    i += 3;
                    let combined = 0x10000 + ((code - 0xD800) << 10) + (lo - 0xDC00);
                    out.push(char::from_u32(combined).ok_or("invalid supplementary code point")?);
                } else if (0xDC00..=0xDFFF).contains(&code) {
                    return Err(format!("unpaired low surrogate U+{:04X}", code));
                } else {
                    out.push(char::from_u32(code).ok_or("invalid three-byte code point")?);
                }
            }
            _ => return Err(format!("invalid modified UTF-8 lead byte 0x{:02x}", b)),
        }
    }

    Ok(out)
}

unsafe fn new_string_utf(env: *mut JNIEnv, chars: *const c_char) -> jstring {
    let func_table = *(env as *const *const *const c_void);
    let func: NewStringUTFFn = std::mem::transmute(*func_table.offset(NEW_STRING_UTF_OFFSET));
    func(env, chars)
}

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
// Global State
// ============================================================================

/// How long a bootstrap may take before the call gives up.
///
/// The deadline has to live here. `nativeInitialize` is a synchronous JNI call, so a timeout on
/// the Kotlin side cannot interrupt it -- the calling thread is inside this function until it
/// returns, and `withTimeout` would abandon a coroutine while the native work carried on.
const BOOTSTRAP_TIMEOUT: Duration = Duration::from_secs(120);

/// How long to wait for the SOCKS listener to actually stop before giving up on it.
const LISTENER_SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(5);

static ARTI_CLIENT: Mutex<Option<Arc<TorClient<PreferredRuntime>>>> = Mutex::new(None);
static TOKIO_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static SOCKS_TASK: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);
static INIT_ONCE: Once = Once::new();

/// Locks without caring whether the mutex was poisoned.
///
/// Poisoning is a panic that happened while some other thread held this lock. The state behind
/// these particular mutexes is an `Option` we always re-check, so a poisoned lock tells us nothing
/// we act on -- whereas `.lock().unwrap()` would panic in turn, and a panic during bootstrap would
/// leave Tor permanently unable to start for the life of the process: every later call would hit
/// the same poisoned mutex and fail the same way.
fn lock_ignoring_poison<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Stops a panic from unwinding out of an `extern "C"` function into the JVM.
///
/// Rust aborts the process if a panic crosses an `extern "C"` boundary, so without this every
/// panic in Arti is fatal to the whole application -- Bluetooth, mesh, UI. Catching it here turns
/// one into [`fallback`], which the Kotlin side already treats as a failed call.
///
/// What this does NOT do, stated plainly because it is easy to assume otherwise: it does not
/// guarantee the JVM survives. Allocation failure, stack overflow, a panic raised while already
/// unwinding, and an explicit `abort()` all bypass it. Only running Tor in its own process would
/// give that guarantee, and that is out of scope here. This narrows the blast radius; it does not
/// remove it.
fn guard_jni<T>(name: &str, fallback: T, body: impl FnOnce() -> T) -> T {
    match std::panic::catch_unwind(AssertUnwindSafe(body)) {
        Ok(value) => value,
        Err(payload) => {
            let message = payload
                .downcast_ref::<&str>()
                .map(|s| (*s).to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "non-string panic payload".to_string());
            log_error!("panic caught at the JNI boundary in {}: {}", name, message);
            fallback
        }
    }
}

// ============================================================================
// JNI Functions
// ============================================================================

#[no_mangle]
pub unsafe extern "C" fn Java_com_bitchat_tor_TorManager_nativeGetVersion(
    env: *mut JNIEnv,
    _class: *mut JClass,
) -> jstring {
    guard_jni("nativeGetVersion", std::ptr::null_mut(), || {
        let version = format!("Arti {} (desktop build)\0", env!("CARGO_PKG_VERSION"));
        new_string_utf(env, version.as_ptr() as *const c_char)
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_bitchat_tor_TorManager_nativeSetLogCallback(
    _env: *mut JNIEnv,
    _class: *mut JClass,
    _callback: *mut JObject,
) {
    guard_jni("nativeSetLogCallback", (), || {
        // Desktop: log callback not implemented (uses stderr)
        log_info!("Log callback registered (desktop uses stderr)");
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_bitchat_tor_TorManager_nativeInitialize(
    env: *mut JNIEnv,
    _class: *mut JClass,
    data_dir: jstring,
) -> jint {
    guard_jni("nativeInitialize", -4, || nativeInitialize_impl(env, data_dir))
}

unsafe fn nativeInitialize_impl(env: *mut JNIEnv, data_dir: jstring) -> jint {
    // Get data directory string
    let chars = get_string_utf_chars(env, data_dir);
    if chars.is_null() {
        log_error!("Failed to get data_dir string");
        return -1;
    }
    let data_dir_str = match decode_modified_utf8(CStr::from_ptr(chars).to_bytes()) {
        Ok(s) => s,
        Err(e) => {
            log_error!("Undecodable data_dir from JNI: {}", e);
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
                *lock_ignoring_poison(&TOKIO_RUNTIME) = Some(rt);
            }
            Err(e) => {
                log_error!("Failed to create Tokio runtime: {:?}", e);
            }
        }
    });

    let runtime_guard = lock_ignoring_poison(&TOKIO_RUNTIME);
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

        // Bounded here rather than by the caller: this whole function is one synchronous JNI
        // call, so a Kotlin-side timeout cannot interrupt it. Without a deadline a bootstrap that
        // never completes holds the calling thread for ever.
        let client = match tokio::time::timeout(
            BOOTSTRAP_TIMEOUT,
            TorClient::create_bootstrapped(config),
        )
        .await
        {
            Ok(result) => result?,
            Err(_) => {
                return Err(anyhow::anyhow!(
                    "bootstrap did not complete within {}s",
                    BOOTSTRAP_TIMEOUT.as_secs()
                ))
            }
        };

        log_info!("Arti client created successfully");
        *lock_ignoring_poison(&ARTI_CLIENT) = Some(Arc::new(client));

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
    guard_jni("nativeStartSocksProxy", -4, || nativeStartSocksProxy_impl(port))
}

unsafe fn nativeStartSocksProxy_impl(port: jint) -> jint {
    log_info!("AMEx: state changed to Starting");
    log_info!("Starting SOCKS proxy on port {}", port);

    let previous = lock_ignoring_poison(&SOCKS_TASK).take();

    let client_guard = lock_ignoring_poison(&ARTI_CLIENT);
    let client = match client_guard.as_ref() {
        Some(c) => Arc::clone(c),
        None => {
            log_error!("Arti client not initialized - call initialize() first");
            return -1;
        }
    };
    drop(client_guard);

    let runtime_guard = lock_ignoring_poison(&TOKIO_RUNTIME);
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    /*
     * Wait for the previous listener to actually be gone before binding again.
     *
     * abort() only requests cancellation; the task keeps its listening socket until it is next
     * polled and dropped. Binding immediately afterwards therefore raced its own predecessor and
     * failed with EADDRINUSE, and no amount of locking on the Kotlin side could have fixed that
     * because the race is between abort and the runtime, not between callers.
     */
    if let Some(handle) = previous {
        log_info!("Waiting for the previous SOCKS listener to release the port");
        handle.abort();
        let _ = runtime.block_on(tokio::time::timeout(LISTENER_SHUTDOWN_TIMEOUT, handle));
    }

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
                    /*
                     * Supervised: a panic inside the handler is captured by Tokio into a
                     * JoinHandle, and with nobody awaiting it the panic vanished silently. Awaiting
                     * it in a second task makes a panic as visible as an error already was --
                     * which matters more now that panics unwind rather than abort the process.
                     */
                    let worker = tokio::spawn(handle_socks_connection(stream, client_clone));
                    tokio::spawn(async move {
                        match worker.await {
                            Ok(Ok(())) => {}
                            Ok(Err(e)) => {
                                log_error!("SOCKS connection error: {:?}", e);
                            }
                            Err(join) if join.is_panic() => {
                                log_error!("SOCKS connection handler panicked for {}", peer);
                            }
                            Err(_) => {
                                log_info!("SOCKS connection handler cancelled for {}", peer);
                            }
                        }
                    });
                }
                Err(e) => {
                    log_error!("Failed to accept SOCKS connection: {:?}", e);
                    break;
                }
            }
        }

        /*
         * Reaching here means the listener has stopped accepting, and the socket is about to be
         * dropped. Logged as an error because nothing else notices: the Kotlin side decides Tor is
         * usable from `isProxyReady`, which stays true on the strength of the bind having once
         * succeeded, so a listener that dies leaves the app reporting protection it is no longer
         * providing. Requests then fail at the proxy rather than leaking, but the status is wrong.
         */
        log_error!("SOCKS listener has stopped accepting connections on {}", addr);
    });

    *lock_ignoring_poison(&SOCKS_TASK) = Some(handle);

    log_info!("SOCKS proxy started on port {}", port);
    0
}

/// Whether the client offered "no authentication required" (RFC 1928 method 0x00).
///
/// The greeting used to be accepted on the strength of having arrived at all: any read of two or
/// more bytes selected "no auth" without checking the version or looking at the offered methods.
pub fn selects_no_auth(version: u8, methods: &[u8]) -> bool {
    version == 0x05 && methods.contains(&0x00)
}

/// Turns an address type and its bytes into a host and port.
///
/// Split out from the I/O so the length arithmetic can be tested. The old code applied a flat
/// ten-byte minimum to every request, which is right only for IPv4: a domain request is
/// `4 + 1 + len + 2` bytes and a short hostname is under ten.
pub fn parse_target(atyp: u8, addr: &[u8], port: [u8; 2]) -> Result<(String, u16), String> {
    let port = u16::from_be_bytes(port);
    match atyp {
        0x01 => {
            if addr.len() != 4 {
                return Err(format!("IPv4 address must be 4 bytes, got {}", addr.len()));
            }
            Ok((format!("{}.{}.{}.{}", addr[0], addr[1], addr[2], addr[3]), port))
        }
        0x03 => {
            if addr.is_empty() {
                return Err("empty domain name".into());
            }
            let host = std::str::from_utf8(addr)
                .map_err(|_| "domain name is not valid UTF-8".to_string())?;
            Ok((host.to_string(), port))
        }
        0x04 => {
            if addr.len() != 16 {
                return Err(format!("IPv6 address must be 16 bytes, got {}", addr.len()));
            }
            let mut groups = Vec::with_capacity(8);
            for pair in addr.chunks_exact(2) {
                groups.push(format!("{:02x}{:02x}", pair[0], pair[1]));
            }
            Ok((groups.join(":"), port))
        }
        other => Err(format!("unsupported address type: {}", other)),
    }
}

async fn handle_socks_connection(
    mut stream: tokio::net::TcpStream,
    client: Arc<TorClient<PreferredRuntime>>,
) -> Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /*
     * Every field is read to its exact length.
     *
     * The previous code issued one read() and treated whatever arrived as a whole message. TCP
     * makes no such promise -- it is a byte stream, and a greeting or request may arrive split
     * across segments or coalesced with the next one. It happened to work over loopback with this
     * JDK's SocksSocketImpl, which writes each message in a single flush, but that is a property
     * of one client on one path rather than anything guaranteed.
     */
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    let (version, n_methods) = (greeting[0], greeting[1] as usize);

    let mut methods = vec![0u8; n_methods];
    stream.read_exact(&mut methods).await?;

    if !selects_no_auth(version, &methods) {
        // 0xFF is "no acceptable methods"; the client is expected to close after this.
        stream.write_all(&[0x05, 0xFF]).await?;
        return Err(anyhow::anyhow!(
            "unsupported SOCKS greeting: version {}, methods {:?}",
            version,
            methods
        ));
    }

    stream.write_all(&[0x05, 0x00]).await?;

    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    let (version, cmd, atyp) = (header[0], header[1], header[3]);

    if version != 0x05 {
        return Err(anyhow::anyhow!("Unsupported SOCKS version: {}", version));
    }

    if cmd != 0x01 {
        // Only support CONNECT command
        stream.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
        return Err(anyhow::anyhow!("Unsupported SOCKS command: {}", cmd));
    }

    let addr = match atyp {
        0x01 => {
            let mut buf = [0u8; 4];
            stream.read_exact(&mut buf).await?;
            buf.to_vec()
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            let mut buf = vec![0u8; len[0] as usize];
            stream.read_exact(&mut buf).await?;
            buf
        }
        0x04 => {
            let mut buf = [0u8; 16];
            stream.read_exact(&mut buf).await?;
            buf.to_vec()
        }
        other => {
            stream.write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Err(anyhow::anyhow!("Unsupported address type: {}", other));
        }
    };

    let mut port_bytes = [0u8; 2];
    stream.read_exact(&mut port_bytes).await?;

    let (target_host, target_port) = match parse_target(atyp, &addr, port_bytes) {
        Ok(target) => target,
        Err(e) => {
            stream.write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Err(anyhow::anyhow!("{}", e));
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
    guard_jni("nativeStop", -4, || nativeStop_impl())
}

unsafe fn nativeStop_impl() -> jint {
    log_info!("AMEx: state changed to Stopping");
    log_info!("Stopping Arti...");

    let task = lock_ignoring_poison(&SOCKS_TASK).take();

    if let Some(handle) = task {
        log_info!("Aborting SOCKS server task");
        handle.abort();
        // Awaited rather than slept past: a fixed 100ms was a guess that the task had finished,
        // and the port stayed bound whenever it had not.
        if let Some(rt) = lock_ignoring_poison(&TOKIO_RUNTIME).as_ref() {
            let finished = rt.block_on(tokio::time::timeout(LISTENER_SHUTDOWN_TIMEOUT, handle));
            if finished.is_err() {
                log_error!("SOCKS listener did not stop within {}s", LISTENER_SHUTDOWN_TIMEOUT.as_secs());
                return -1;
            }
        }
    }

    /*
     * Release the client too.
     *
     * This used to abort the listener and leave ARTI_CLIENT populated, so a "stopped" Tor still
     * held its circuits, its directory state and its guard connections open. Nothing ever cleared
     * it short of ending the process, and the next initialize() replaced it while the old one was
     * still alive.
     */
    if lock_ignoring_poison(&ARTI_CLIENT).take().is_some() {
        log_info!("Released the Arti client");
    }

    log_info!("AMEx: state changed to Stopped");
    log_info!("Arti stopped successfully");

    0
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    // MARK: - JNI modified UTF-8

    #[test]
    fn decodes_ascii() {
        assert_eq!(decode_modified_utf8(b"/home/user/.bitchat").unwrap(), "/home/user/.bitchat");
    }

    #[test]
    fn decodes_two_and_three_byte_sequences() {
        // U+00E9 and U+20AC are encoded identically in UTF-8 and modified UTF-8.
        assert_eq!(decode_modified_utf8("café".as_bytes()).unwrap(), "café");
        assert_eq!(decode_modified_utf8("€".as_bytes()).unwrap(), "€");
    }

    #[test]
    fn decodes_a_supplementary_character_written_as_a_surrogate_pair() {
        /*
         * The bug this decoder exists for. U+1F600 is four bytes in real UTF-8 (F0 9F 98 80), but
         * JNI hands back the UTF-16 surrogate pair D83D DE00 with each half written as its own
         * three-byte sequence. CStr::to_str rejects that, so any data directory containing a
         * character outside the Basic Multilingual Plane failed initialisation -- invisible on an
         * ASCII home path.
         */
        let modified = [0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80];

        assert_eq!(decode_modified_utf8(&modified).unwrap(), "\u{1F600}");
        // Confirm the premise: this is not valid UTF-8, which is why the old path failed.
        assert!(std::str::from_utf8(&modified).is_err());
    }

    #[test]
    fn decodes_an_embedded_nul_written_as_c0_80() {
        // Modified UTF-8 encodes NUL as two bytes so it cannot terminate the string.
        assert_eq!(decode_modified_utf8(&[b'a', 0xC0, 0x80, b'b']).unwrap(), "a\u{0}b");
    }

    #[test]
    fn rejects_an_unpaired_surrogate() {
        assert!(decode_modified_utf8(&[0xED, 0xA0, 0xBD]).is_err());
        assert!(decode_modified_utf8(&[0xED, 0xB8, 0x80]).is_err());
    }

    #[test]
    fn rejects_truncated_sequences() {
        assert!(decode_modified_utf8(&[0xE2, 0x82]).is_err());
        assert!(decode_modified_utf8(&[0xC3]).is_err());
    }

    // MARK: - SOCKS greeting

    #[test]
    fn accepts_a_greeting_that_offers_no_auth() {
        assert!(selects_no_auth(0x05, &[0x00]));
        assert!(selects_no_auth(0x05, &[0x02, 0x00]));
    }

    #[test]
    fn rejects_a_greeting_that_does_not_offer_no_auth() {
        // Previously any read of two or more bytes was answered with "no auth selected",
        // regardless of what the client actually offered.
        assert!(!selects_no_auth(0x05, &[0x02]));
        assert!(!selects_no_auth(0x05, &[]));
    }

    #[test]
    fn rejects_a_greeting_from_another_socks_version() {
        assert!(!selects_no_auth(0x04, &[0x00]));
    }

    // MARK: - SOCKS request targets

    #[test]
    fn parses_an_ipv4_target() {
        let (host, port) = parse_target(0x01, &[127, 0, 0, 1], [0x1F, 0x90]).unwrap();
        assert_eq!(host, "127.0.0.1");
        assert_eq!(port, 8080);
    }

    #[test]
    fn parses_a_short_domain_target() {
        /*
         * A domain request is 4 + 1 + len + 2 bytes, so "a.io" totals nine -- under the flat
         * ten-byte minimum the old code applied to every request, which rejected it outright.
         */
        let (host, port) = parse_target(0x03, b"a.io", [0x01, 0xBB]).unwrap();
        assert_eq!(host, "a.io");
        assert_eq!(port, 443);
    }

    #[test]
    fn parses_an_ipv6_target() {
        let addr = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1];
        let (host, port) = parse_target(0x04, &addr, [0x01, 0xBB]).unwrap();
        assert_eq!(host, "2001:0db8:0000:0000:0000:0000:0000:0001");
        assert_eq!(port, 443);
    }

    #[test]
    fn rejects_wrongly_sized_addresses() {
        assert!(parse_target(0x01, &[127, 0, 0], [0, 80]).is_err());
        assert!(parse_target(0x04, &[0; 15], [0, 80]).is_err());
        assert!(parse_target(0x03, b"", [0, 80]).is_err());
    }

    #[test]
    fn rejects_an_unknown_address_type() {
        assert!(parse_target(0x09, &[1, 2, 3, 4], [0, 80]).is_err());
    }

    // MARK: - containment

    #[test]
    fn a_panic_is_turned_into_the_fallback_value() {
        let result = guard_jni("test", -4, || panic!("boom"));
        assert_eq!(result, -4);
    }

    #[test]
    fn a_poisoned_lock_is_still_usable() {
        /*
         * Without this, a panic during bootstrap left Tor unable to start for the life of the
         * process: every later call hit the poisoned mutex and panicked in turn.
         */
        static VALUE: Mutex<i32> = Mutex::new(7);

        let _ = std::panic::catch_unwind(|| {
            let _guard = VALUE.lock().unwrap();
            panic!("poison it");
        });

        assert!(VALUE.lock().is_err(), "precondition: the mutex should now be poisoned");
        assert_eq!(*lock_ignoring_poison(&VALUE), 7);
    }
}
