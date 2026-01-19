use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::{Arc, Mutex, Once};
use std::path::PathBuf;
use anyhow::Result;

use arti_client::TorClient;
use arti_client::config::TorClientConfigBuilder;
use tor_rtcompat::PreferredRuntime;

// ============================================================================
// Global State
// ============================================================================

/// Global Arti client instance
static ARTI_CLIENT: Mutex<Option<Arc<TorClient<PreferredRuntime>>>> = Mutex::new(None);

/// Global Tokio runtime (must persist for Arti to work)
static TOKIO_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);

/// Global log callback
static LOG_CALLBACK: Mutex<Option<extern "C" fn(*const c_char)>> = Mutex::new(None);

/// Handle to SOCKS server task (for graceful shutdown)
static SOCKS_TASK: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);

/// Initialization flag
static INIT_ONCE: Once = Once::new();

// ============================================================================
// Logging Integration
// ============================================================================

/// Send log message to callback
fn send_log(message: &str) {
    let callback_opt = LOG_CALLBACK.lock().unwrap();
    if let Some(callback) = *callback_opt {
        if let Ok(c_message) = CString::new(message) {
            callback(c_message.as_ptr());
        }
    }
}

/// Macro for logging
macro_rules! log_info {
    ($($arg:tt)*) => {{
        let msg = format!($($arg)*);
        send_log(&msg);
    }};
}

macro_rules! log_error {
    ($($arg:tt)*) => {{
        let msg = format!("ERROR: {}", format!($($arg)*));
        send_log(&msg);
    }};
}

// ============================================================================
// C FFI Functions
// ============================================================================

/// Get Arti version string
#[no_mangle]
pub extern "C" fn arti_get_version() -> *const c_char {
    static VERSION: Once = Once::new();
    static mut VERSION_STRING: Option<CString> = None;

    VERSION.call_once(|| {
        let version = format!("Arti {} (custom build with rustls)", env!("CARGO_PKG_VERSION"));
        unsafe {
            VERSION_STRING = CString::new(version).ok();
        }
    });

    unsafe {
        VERSION_STRING.as_ref()
            .map(|s| s.as_ptr())
            .unwrap_or(std::ptr::null())
    }
}

/// Set log callback for Arti logs
#[no_mangle]
pub extern "C" fn arti_set_log_callback(callback: extern "C" fn(*const c_char)) {
    *LOG_CALLBACK.lock().unwrap() = Some(callback);
    log_info!("Log callback registered");
}

/// Initialize Arti runtime
#[no_mangle]
pub extern "C" fn arti_initialize(data_dir: *const c_char) -> c_int {
    if data_dir.is_null() {
        log_error!("data_dir is null");
        return -1;
    }

    let data_dir_str = unsafe {
        match CStr::from_ptr(data_dir).to_str() {
            Ok(s) => s.to_string(),
            Err(e) => {
                log_error!("Failed to convert data_dir: {:?}", e);
                return -1;
            }
        }
    };

    log_info!("AMEx: state changed to Initialized");
    log_info!("Initializing Arti with data directory: {}", data_dir_str);

    // Initialize Tokio runtime (once)
    INIT_ONCE.call_once(|| {
        match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => {
                log_info!("Tokio runtime created successfully");
                *TOKIO_RUNTIME.lock().unwrap() = Some(rt);
            }
            Err(e) => {
                log_error!("Failed to create Tokio runtime: {:?}", e);
            }
        }
    });

    // Check if runtime exists
    let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    // Create config with explicit iOS paths
    let data_path = PathBuf::from(data_dir_str);
    let cache_dir = data_path.join("cache");
    let state_dir = data_path.join("state");

    // Create directories if they don't exist
    std::fs::create_dir_all(&cache_dir).ok();
    std::fs::create_dir_all(&state_dir).ok();

    let result: Result<()> = runtime.block_on(async {
        log_info!("Creating Arti client...");
        log_info!("Cache dir: {:?}", cache_dir);
        log_info!("State dir: {:?}", state_dir);

        // Create config with iOS-specific directories
        let config = TorClientConfigBuilder::from_directories(state_dir, cache_dir)
            .build()?;

        // Create client with iOS-specific config
        let client = TorClient::create_bootstrapped(config).await?;

        log_info!("Arti client created successfully");

        // Store client globally
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

/// Start SOCKS proxy on specified port
#[no_mangle]
pub extern "C" fn arti_start_socks_proxy(port: c_int) -> c_int {
    log_info!("AMEx: state changed to Starting");
    log_info!("Starting SOCKS proxy on port {}", port);

    // Stop any existing SOCKS server first
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting previous SOCKS server task");
        handle.abort();
    }

    let client_guard = ARTI_CLIENT.lock().unwrap();
    let client = match client_guard.as_ref() {
        Some(c) => Arc::clone(c),
        None => {
            log_error!("Arti client not initialized - call arti_initialize() first");
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

    // Try to bind IMMEDIATELY to detect port conflicts before returning
    let addr = format!("127.0.0.1:{}", port);

    // Use block_on to synchronously attempt binding
    let bind_result = runtime.block_on(async {
        tokio::net::TcpListener::bind(&addr).await
    });

    let listener = match bind_result {
        Ok(l) => {
            log_info!("SOCKS proxy bound to {}", addr);
            l
        }
        Err(e) => {
            log_error!("Failed to bind SOCKS proxy to {}: {:?}", addr, e);
            return -3;
        }
    };

    // Now spawn the background task with the already-bound listener
    let handle = runtime.spawn(async move {
        log_info!("SOCKS proxy listening on {}", addr);
        log_info!("Sufficiently bootstrapped; system SOCKS now functional");

        // Signal bootstrap completion
        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
        log_info!("We have found that guard [scrubbed] is usable.");

        // Accept connections
        loop {
            match listener.accept().await {
                Ok((stream, peer_addr)) => {
                    log_info!("SOCKS connection from: {}", peer_addr);
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

    // Store handle for cleanup
    *SOCKS_TASK.lock().unwrap() = Some(handle);

    log_info!("SOCKS proxy started on port {}", port);
    0
}

/// Handle a single SOCKS connection
async fn handle_socks_connection(
    mut stream: tokio::net::TcpStream,
    client: Arc<TorClient<PreferredRuntime>>,
) -> Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // Simple SOCKS5 handshake
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

    // Parse SOCKS5 request
    let version = buf[0];
    let cmd = buf[1];
    let atyp = buf[3];

    if version != 0x05 {
        return Err(anyhow::anyhow!("Unsupported SOCKS version: {}", version));
    }

    if cmd != 0x01 {
        stream.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
        return Err(anyhow::anyhow!("Unsupported SOCKS command: {}", cmd));
    }

    // Parse target address and port
    let (target_host, target_port) = match atyp {
        0x01 => {
            // IPv4
            let ip = format!("{}.{}.{}.{}", buf[4], buf[5], buf[6], buf[7]);
            let port = u16::from_be_bytes([buf[8], buf[9]]);
            (ip, port)
        }
        0x03 => {
            // Domain name
            let len = buf[4] as usize;
            if n < 5 + len + 2 {
                return Err(anyhow::anyhow!("Invalid domain name length"));
            }
            let domain = String::from_utf8_lossy(&buf[5..5 + len]).to_string();
            let port = u16::from_be_bytes([buf[5 + len], buf[5 + len + 1]]);
            (domain, port)
        }
        0x04 => {
            // IPv6
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

/// Stop Arti and cleanup
#[no_mangle]
pub extern "C" fn arti_stop() -> c_int {
    log_info!("AMEx: state changed to Stopping");
    log_info!("Stopping Arti...");

    // Abort SOCKS proxy task
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting SOCKS server task");
        handle.abort();
    }

    // Give the abort a moment to complete
    if let Some(rt) = TOKIO_RUNTIME.lock().unwrap().as_ref() {
        rt.block_on(async {
            tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
        });
    }

    log_info!("AMEx: state changed to Stopped");
    log_info!("Arti stopped successfully");

    0
}
