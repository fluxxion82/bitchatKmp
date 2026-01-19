#ifndef ARTI_MACOS_H
#define ARTI_MACOS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Log callback function type
typedef void (*arti_log_callback_t)(const char* message);

/// Get Arti version string
/// @return Version string (caller must NOT free)
const char* arti_get_version(void);

/// Set log callback for Arti logs
/// @param callback Function to call with log messages
void arti_set_log_callback(arti_log_callback_t callback);

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

#endif // ARTI_MACOS_H
