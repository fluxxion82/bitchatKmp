#ifndef ARTI_LINUX_H
#define ARTI_LINUX_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Log callback function type
typedef void (*arti_log_callback_t)(const char* message);
typedef void (*arti_status_callback_t)(int32_t state, int32_t port, uint64_t generation, const char* message);

/// Get Arti version string
/// @return Version string (caller must NOT free)
const char* arti_get_version(void);

/// Set log callback for Arti logs
/// @param callback Function to call with log messages
void arti_set_log_callback(arti_log_callback_t callback);

/// Message memory exists only during this callback; callers must copy it immediately.
void arti_set_status_callback(arti_status_callback_t callback);

/// Starts cancellable bootstrap and a SOCKS listener. Port 0 chooses a loopback port.
int32_t arti_start(const char* data_dir, int32_t requested_port, uint64_t generation);

/// Cancels and joins bootstrap, listener, and child connection tasks before STOPPED.
int32_t arti_stop_generation(uint64_t generation);

/// Initialize Arti runtime
/// @param data_dir Path to data directory
/// @return 0 on success, negative on error
int32_t arti_initialize(const char* data_dir);

/// Start SOCKS proxy on specified port
/// @param port Port number for SOCKS proxy
/// @return 0 on success, negative on error
int32_t arti_start_socks_proxy(int32_t port);

/// Stop Arti and cleanup
/// @return 0 on success, negative on error
int32_t arti_stop(void);

#ifdef __cplusplus
}
#endif

#endif // ARTI_LINUX_H
