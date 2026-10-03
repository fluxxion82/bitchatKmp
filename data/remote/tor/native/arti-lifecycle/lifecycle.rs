use anyhow::{anyhow, Result};
use arti_client::config::TorClientConfigBuilder;
use arti_client::TorClient;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::sync::oneshot;
use tokio::task::JoinHandle;
use tokio::time::{timeout, Duration};
use tor_rtcompat::PreferredRuntime;

const STATUS_INITIALIZING: c_int = 1;
const STATUS_SOCKS_LISTENING: c_int = 2;
const STATUS_READY: c_int = 3;
const STATUS_STOPPED: c_int = 4;
const STATUS_ERROR: c_int = 5;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(15);

type StatusCallback = extern "C" fn(c_int, c_int, u64, *const c_char);
type LogCallback = extern "C" fn(*const c_char);

struct NativeState {
    runtime: Arc<tokio::runtime::Runtime>,
    generation: u64,
    client: Option<Arc<TorClient<PreferredRuntime>>>,
    bootstrap: Option<JoinHandle<()>>,
    listener: Option<JoinHandle<()>>,
    children: Vec<JoinHandle<()>>,
    // Aborted children remain owned here until a later stop/start observes their completion.
    draining: Vec<JoinHandle<()>>,
}

static STATE: OnceLock<Mutex<NativeState>> = OnceLock::new();
static STATUS_CALLBACK: Mutex<Option<StatusCallback>> = Mutex::new(None);
static LOG_CALLBACK: Mutex<Option<LogCallback>> = Mutex::new(None);
static LIFECYCLE: Mutex<()> = Mutex::new(());
// Native callbacks for one generation are serialized. A terminal callback invalidates the
// generation while holding this lock, so READY cannot be published after ERROR.
static PUBLICATIONS: Mutex<()> = Mutex::new(());

fn state() -> &'static Mutex<NativeState> {
    STATE.get_or_init(|| {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("Tokio runtime creation failed");
        Mutex::new(NativeState {
            runtime: Arc::new(runtime),
            generation: 0,
            client: None,
            bootstrap: None,
            listener: None,
            children: Vec::new(),
            draining: Vec::new(),
        })
    })
}

fn report(status: c_int, port: c_int, generation: u64, message: &str) {
    let callback = *STATUS_CALLBACK
        .lock()
        .expect("status callback mutex poisoned");
    if let Some(callback) = callback {
        if let Ok(message) = CString::new(message) {
            callback(status, port, generation, message.as_ptr());
        }
    }
}

fn log(message: &str) {
    let callback = *LOG_CALLBACK.lock().expect("log callback mutex poisoned");
    if let Some(callback) = callback {
        if let Ok(message) = CString::new(message) {
            callback(message.as_ptr());
        }
    }
}

fn is_current(generation: u64) -> bool {
    state()
        .lock()
        .map(|state| state.generation == generation)
        .unwrap_or(false)
}

fn reap_finished_children(children: &mut Vec<JoinHandle<()>>) {
    children.retain(|task| !task.is_finished());
}

fn report_current(status: c_int, port: c_int, generation: u64, message: &str) -> bool {
    let _publication = PUBLICATIONS.lock().expect("publication mutex poisoned");
    if !is_current(generation) {
        return false;
    }
    report(status, port, generation, message);
    true
}

fn join_aborted_for(
    runtime: Arc<tokio::runtime::Runtime>,
    mut tasks: Vec<JoinHandle<()>>,
    shutdown_timeout: Duration,
) -> (bool, Vec<JoinHandle<()>>) {
    for task in &tasks {
        task.abort();
    }
    let completed = runtime.block_on(async {
        timeout(shutdown_timeout, async {
            for task in &mut tasks {
                let _ = task.await;
            }
        })
        .await
        .is_ok()
    });
    tasks.retain(|task| !task.is_finished());
    (completed, tasks)
}

fn join_aborted(runtime: Arc<tokio::runtime::Runtime>, tasks: Vec<JoinHandle<()>>) -> bool {
    join_aborted_for(runtime, tasks, SHUTDOWN_TIMEOUT).0
}

fn stop_locked(generation: u64) -> c_int {
    // Detach under the state lock, then release it before joining. A worker can be waiting on
    // this lock, so joining while it is held makes Tokio cancellation deadlock.
    let (runtime, tasks, had_tasks) = {
        let mut state = state().lock().expect("native state mutex poisoned");
        let had_tasks = state.bootstrap.is_some() || state.listener.is_some() ||
            !state.children.is_empty() || !state.draining.is_empty();
        state.generation = 0;
        state.client = None;
        let mut tasks = Vec::new();
        if let Some(task) = state.bootstrap.take() {
            tasks.push(task);
        }
        if let Some(task) = state.listener.take() {
            tasks.push(task);
        }
        tasks.append(&mut state.children);
        tasks.append(&mut state.draining);
        (Arc::clone(&state.runtime), tasks, had_tasks)
    };
    if !had_tasks {
        return 0;
    }
    let (stopped, unfinished) = join_aborted_for(Arc::clone(&runtime), tasks, SHUTDOWN_TIMEOUT);
    if !stopped {
        state()
            .lock()
            .expect("native state mutex poisoned")
            .draining
            .extend(unfinished);
        report(STATUS_ERROR, 0, generation, "Arti shutdown timed out");
        return -3;
    }
    report(STATUS_STOPPED, 0, generation, "Arti stopped");
    0
}

async fn fail_listener(generation: u64, message: String) {
    let _publication = PUBLICATIONS.lock().expect("publication mutex poisoned");
    let mut state = state().lock().expect("native state mutex poisoned");
    if state.generation != generation {
        return;
    }
    state.generation = 0;
    state.client = None;
    let children = std::mem::take(&mut state.children);
    // Do not hide child handles behind a drain owner: aborting that owner used to drop its handles
    // before their aborts ran. State keeps every handle through stop and a timeout retry.
    for task in &children {
        task.abort();
    }
    state.draining.extend(children);
    drop(state);
    report(STATUS_ERROR, 0, generation, &message);
}

#[no_mangle]
pub extern "C" fn arti_set_status_callback(callback: StatusCallback) {
    *STATUS_CALLBACK
        .lock()
        .expect("status callback mutex poisoned") = Some(callback);
}

#[no_mangle]
pub extern "C" fn arti_set_log_callback(callback: LogCallback) {
    *LOG_CALLBACK.lock().expect("log callback mutex poisoned") = Some(callback);
}

#[no_mangle]
pub extern "C" fn arti_start(
    data_dir: *const c_char,
    requested_port: c_int,
    generation: u64,
) -> c_int {
    if data_dir.is_null() || requested_port < 0 || requested_port > u16::MAX as c_int {
        return -1;
    }
    let data_dir = match unsafe { CStr::from_ptr(data_dir) }.to_str() {
        Ok(value) => PathBuf::from(value),
        Err(_) => return -2,
    };
    let _lifecycle = LIFECYCLE.lock().expect("lifecycle mutex poisoned");
    if stop_locked(generation) != 0 {
        return -3;
    }
    let runtime = {
        let mut state = state().lock().expect("native state mutex poisoned");
        state.generation = generation;
        Arc::clone(&state.runtime)
    };
    report_current(STATUS_INITIALIZING, 0, generation, "Bootstrapping Arti");
    let task = runtime.spawn(async move {
        let cache_dir = data_dir.join("cache");
        let state_dir = data_dir.join("state");
        if let Err(error) =
            std::fs::create_dir_all(&cache_dir).and_then(|_| std::fs::create_dir_all(&state_dir))
        {
            if is_current(generation) {
                report_current(
                    STATUS_ERROR,
                    0,
                    generation,
                    &format!("Cannot create Arti directories: {error}"),
                );
            }
            return;
        }
        let config = match TorClientConfigBuilder::from_directories(state_dir, cache_dir).build() {
            Ok(config) => config,
            Err(error) => {
                if is_current(generation) {
                    report_current(
                        STATUS_ERROR,
                        0,
                        generation,
                        &format!("Arti configuration failed: {error}"),
                    );
                }
                return;
            }
        };
        let client = match TorClient::create_bootstrapped(config).await {
            Ok(client) => client,
            Err(error) => {
                if is_current(generation) {
                    report_current(
                        STATUS_ERROR,
                        0,
                        generation,
                        &format!("Arti bootstrap failed: {error}"),
                    );
                }
                return;
            }
        };
        let listener =
            match tokio::net::TcpListener::bind(("127.0.0.1", requested_port as u16)).await {
                Ok(listener) => listener,
                Err(error) => {
                    if is_current(generation) {
                        report_current(
                            STATUS_ERROR,
                            0,
                            generation,
                            &format!("SOCKS bind failed: {error}"),
                        );
                    }
                    return;
                }
            };
        let port = match listener.local_addr() {
            Ok(address) => address.port() as c_int,
            Err(error) => {
                report_current(
                    STATUS_ERROR,
                    0,
                    generation,
                    &format!("SOCKS address failed: {error}"),
                );
                return;
            }
        };
        let (listener_start, listener_ready) = oneshot::channel();
        {
            let mut native_state = state().lock().expect("native state mutex poisoned");
            if native_state.generation != generation {
                return;
            }
            native_state.client = Some(Arc::clone(&client));
            let listener_task = tokio::spawn(async move {
                if listener_ready.await.is_err() {
                    return;
                }
                loop {
                    let (stream, _) = match listener.accept().await {
                        Ok(connection) => connection,
                        Err(error) => {
                            fail_listener(generation, format!("SOCKS accept failed: {error}")).await;
                            return;
                        }
                    };
                    let mut state = state().lock().expect("native state mutex poisoned");
                    if state.generation != generation {
                        return;
                    }
                    reap_finished_children(&mut state.children);
                    let connection_client = Arc::clone(&client);
                    let child = tokio::spawn(async move {
                        let _ = handle_socks_connection(stream, connection_client).await;
                    });
                    state.children.push(child);
                }
            });
            native_state.listener = Some(listener_task);
        }
        if !report_current(STATUS_SOCKS_LISTENING, port, generation, "SOCKS listener bound") ||
            !report_current(STATUS_READY, port, generation, "Arti ready") {
            return;
        }
        let _ = listener_start.send(());
    });
    let mut state = state().lock().expect("native state mutex poisoned");
    if state.generation == generation {
        state.bootstrap = Some(task);
    } else {
        task.abort();
    }
    drop(state);
    0
}

#[no_mangle]
pub extern "C" fn arti_stop_generation(generation: u64) -> c_int {
    let _lifecycle = LIFECYCLE.lock().expect("lifecycle mutex poisoned");
    stop_locked(generation)
}

async fn read_socks_greeting<R: AsyncRead + Unpin>(stream: &mut R) -> Result<()> {
    let mut greeting = [0_u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 5 || greeting[1] == 0 {
        return Err(anyhow!("invalid SOCKS5 greeting"));
    }
    let mut methods = vec![0_u8; greeting[1] as usize];
    stream.read_exact(&mut methods).await?;
    if !methods.contains(&0) {
        return Err(anyhow!("SOCKS5 client does not offer no-auth"));
    }
    Ok(())
}

async fn read_socks_connect_request<R: AsyncRead + Unpin>(stream: &mut R) -> Result<(String, u16)> {
    let mut request = [0_u8; 4];
    stream.read_exact(&mut request).await?;
    if request[0] != 5 || request[1] != 1 || request[2] != 0 {
        return Err(anyhow!("invalid SOCKS5 CONNECT request"));
    }
    match request[3] {
        1 => {
            let mut address = [0_u8; 6];
            stream.read_exact(&mut address).await?;
            Ok((
                format!(
                    "{}.{}.{}.{}",
                    address[0], address[1], address[2], address[3]
                ),
                u16::from_be_bytes([address[4], address[5]]),
            ))
        }
        3 => {
            let mut length = [0_u8; 1];
            stream.read_exact(&mut length).await?;
            if length[0] == 0 {
                return Err(anyhow!("empty SOCKS5 domain"));
            }
            let mut address = vec![0_u8; length[0] as usize + 2];
            stream.read_exact(&mut address).await?;
            let host = std::str::from_utf8(&address[..length[0] as usize])?.to_owned();
            let port =
                u16::from_be_bytes([address[length[0] as usize], address[length[0] as usize + 1]]);
            Ok((host, port))
        }
        4 => {
            let mut address = [0_u8; 18];
            stream.read_exact(&mut address).await?;
            let host =
                std::net::Ipv6Addr::from(<[u8; 16]>::try_from(&address[..16]).unwrap()).to_string();
            Ok((host, u16::from_be_bytes([address[16], address[17]])))
        }
        _ => Err(anyhow!("unsupported SOCKS5 address type")),
    }
}

async fn handle_socks_connection(
    mut stream: tokio::net::TcpStream,
    client: Arc<TorClient<PreferredRuntime>>,
) -> Result<()> {
    timeout(HANDSHAKE_TIMEOUT, read_socks_greeting(&mut stream)).await??;
    stream.write_all(&[5, 0]).await?;
    let (target_host, target_port) =
        timeout(HANDSHAKE_TIMEOUT, read_socks_connect_request(&mut stream)).await??;
    log(&format!("SOCKS5 CONNECT to {target_host}:{target_port}"));
    let tor_stream = client.connect((target_host.as_str(), target_port)).await?;
    log(&format!(
        "Tor connection established to {target_host}:{target_port}"
    ));
    stream.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).await?;
    let (mut client_read, mut client_write) = stream.split();
    let (mut tor_read, mut tor_write) = tor_stream.split();
    tokio::select! { _ = tokio::io::copy(&mut client_read, &mut tor_write) => {}, _ = tokio::io::copy(&mut tor_read, &mut client_write) => {} }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;
    use tokio::io::AsyncWriteExt;

    static TEST_SERIAL: StdMutex<()> = StdMutex::new(());
    static TEST_STATUSES: StdMutex<Vec<c_int>> = StdMutex::new(Vec::new());

    extern "C" fn record_status(status: c_int, _: c_int, _: u64, _: *const c_char) {
        TEST_STATUSES.lock().unwrap().push(status);
    }

    #[tokio::test]
    async fn reads_fragmented_domain_connect_request() {
        let (mut writer, mut reader) = tokio::io::duplex(64);
        let writer = tokio::spawn(async move {
            for part in [
                &[5, 1][..],
                &[0],
                &[5, 1, 0, 3, 11],
                b"example.com",
                &[1, 187],
            ] {
                writer.write_all(part).await.unwrap();
            }
        });
        read_socks_greeting(&mut reader).await.unwrap();
        assert_eq!(
            read_socks_connect_request(&mut reader).await.unwrap(),
            ("example.com".to_owned(), 443)
        );
        writer.await.unwrap();
    }

    #[tokio::test]
    async fn rejects_invalid_greeting_before_connecting() {
        let (mut writer, mut reader) = tokio::io::duplex(16);
        writer.write_all(&[4, 1, 0]).await.unwrap();
        assert!(read_socks_greeting(&mut reader).await.is_err());
    }

    #[tokio::test]
    async fn reaps_finished_connection_tasks() {
        let mut children = vec![tokio::spawn(async {})];
        tokio::task::yield_now().await;

        reap_finished_children(&mut children);

        assert!(children.is_empty());
    }

    #[test]
    fn shutdown_aborts_and_joins_a_detached_task() {
        let runtime = Arc::clone(&state().lock().unwrap().runtime);
        let task = runtime.spawn(async { std::future::pending::<()>().await });

        assert!(join_aborted(runtime, vec![task]));
    }

    #[test]
    fn shutdown_timeout_retains_unfinished_task_ownership() {
        let runtime = Arc::clone(&state().lock().unwrap().runtime);
        let (started_tx, started_rx) = std::sync::mpsc::sync_channel(1);
        let task = runtime.spawn(async move {
            started_tx.send(()).unwrap();
            std::thread::sleep(Duration::from_millis(50));
        });
        started_rx.recv().unwrap();

        let (stopped, unfinished) =
            join_aborted_for(Arc::clone(&runtime), vec![task], Duration::from_millis(1));

        assert!(!stopped);
        assert_eq!(unfinished.len(), 1, "timeout dropped the old-generation handle");
        runtime.block_on(async move {
            for task in unfinished {
                let _ = task.await;
            }
        });
    }

    #[test]
    fn stop_aborts_children_owned_by_a_failed_listener_drain() {
        let _serial = TEST_SERIAL.lock().unwrap();
        let runtime = Arc::clone(&state().lock().unwrap().runtime);
        let released = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let notify = Arc::new(tokio::sync::Notify::new());
        let child_released = Arc::clone(&released);
        let child_notify = Arc::clone(&notify);
        let child = runtime.spawn(async move {
            child_notify.notified().await;
            child_released.store(true, std::sync::atomic::Ordering::SeqCst);
        });
        {
            let mut native = state().lock().unwrap();
            native.generation = 92;
            native.bootstrap = None;
            native.listener = None;
            native.children = vec![child];
            native.draining.clear();
        }

        runtime.block_on(fail_listener(92, "synthetic accept failure".to_owned()));
        assert_eq!(stop_locked(92), 0);
        runtime.block_on(async {
            notify.notify_waiters();
            tokio::time::sleep(Duration::from_millis(20)).await;
        });

        assert!(!released.load(std::sync::atomic::Ordering::SeqCst));
    }

    #[test]
    fn listener_error_is_terminal_for_its_generation() {
        let _serial = TEST_SERIAL.lock().unwrap();
        TEST_STATUSES.lock().unwrap().clear();
        *STATUS_CALLBACK.lock().unwrap() = Some(record_status);
        {
            let mut native = state().lock().unwrap();
            native.generation = 91;
            native.client = None;
            native.bootstrap = None;
            native.listener = None;
            native.children.clear();
            native.draining.clear();
        }

        let runtime = Arc::clone(&state().lock().unwrap().runtime);
        runtime.block_on(fail_listener(91, "synthetic accept failure".to_owned()));
        assert!(!report_current(STATUS_READY, 1, 91, "must not resurrect"));
        assert_eq!(*TEST_STATUSES.lock().unwrap(), vec![STATUS_ERROR]);

        stop_locked(91);
        *STATUS_CALLBACK.lock().unwrap() = None;
    }

    /// Both wrapper crates compile this file, so their C headers must describe exactly this ABI.
    #[test]
    fn c_headers_declare_the_shared_abi() {
        let headers = [
            ("arti_linux.h", "ARTI_LINUX_H", include_str!("../arti-linux-wrapper/arti_linux.h")),
            ("arti_ios.h", "ARTI_IOS_H", include_str!("../arti-ios-wrapper/arti_ios.h")),
            ("arti_macos.h", "ARTI_MACOS_H", include_str!("../arti-ios-wrapper/arti_macos.h")),
        ];
        let constants = [
            ("ARTI_STATUS_INITIALIZING", STATUS_INITIALIZING),
            ("ARTI_STATUS_SOCKS_LISTENING", STATUS_SOCKS_LISTENING),
            ("ARTI_STATUS_READY", STATUS_READY),
            ("ARTI_STATUS_STOPPED", STATUS_STOPPED),
            ("ARTI_STATUS_ERROR", STATUS_ERROR),
        ];
        let exported = [
            "void arti_set_log_callback(arti_log_callback_t callback);",
            "void arti_set_status_callback(arti_status_callback_t callback);",
            "int32_t arti_start(const char* data_dir, int32_t requested_port, uint64_t generation);",
            "int32_t arti_stop_generation(uint64_t generation);",
        ];
        let removed = ["arti_initialize", "arti_start_socks_proxy", "arti_stop(", "arti_get_version"];
        for (name, _, header) in headers {
            for (constant, value) in constants {
                let define = format!("#define {constant} {value}\n");
                assert!(header.contains(&define), "{name} lacks `{}`", define.trim());
            }
            for declaration in exported {
                assert!(header.contains(declaration), "{name} lacks `{declaration}`");
            }
            for symbol in removed {
                assert!(!header.contains(symbol), "{name} still declares {symbol}, which this library does not export");
            }
        }
        let normalized: Vec<String> = headers
            .iter()
            .map(|(_, guard, header)| header.replace(guard, "ARTI_H"))
            .collect();
        assert_eq!(normalized[0], normalized[1], "arti_linux.h and arti_ios.h differ beyond the include guard");
        assert_eq!(normalized[1], normalized[2], "arti_ios.h and arti_macos.h differ beyond the include guard");
    }
}
