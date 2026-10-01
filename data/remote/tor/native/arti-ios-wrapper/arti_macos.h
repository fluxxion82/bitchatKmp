#ifndef ARTI_MACOS_H
#define ARTI_MACOS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Values of the state argument of arti_status_callback_t. STOPPED and ERROR end a generation,
/// except that arti_start(N) may pre-stop leftovers and publish STOPPED tagged N before it then
/// publishes INITIALIZING and READY for N.
#define ARTI_STATUS_INITIALIZING 1
#define ARTI_STATUS_SOCKS_LISTENING 2
#define ARTI_STATUS_READY 3
#define ARTI_STATUS_STOPPED 4
#define ARTI_STATUS_ERROR 5

/// Log callback function type
typedef void (*arti_log_callback_t)(const char* message);

/// Status callback. port is the bound loopback SOCKS port for SOCKS_LISTENING and READY, 0 otherwise;
/// generation is the value passed to arti_start. The callback runs on Arti's threads, or on the
/// caller's thread inside arti_start/arti_stop_generation, while native locks are held: copy message
/// (valid only during the call) and return without blocking or calling back into this library.
typedef void (*arti_status_callback_t)(int32_t state, int32_t port, uint64_t generation, const char* message);

/// Set log callback for Arti logs. Message memory exists only during the callback.
void arti_set_log_callback(arti_log_callback_t callback);

/// Message memory exists only during this callback; callers must copy it immediately.
void arti_set_status_callback(arti_status_callback_t callback);

/// Pre-stops any earlier generation, then starts cancellable bootstrap and a SOCKS listener for
/// this one. That pre-stop may publish STOPPED tagged with this generation before INITIALIZING and
/// READY for it. Port 0 chooses a loopback port, reported through the status callback.
/// @return 0 on success, negative on error
int32_t arti_start(const char* data_dir, int32_t requested_port, uint64_t generation);

/// Cancels and joins bootstrap, listener, and child connection tasks before STOPPED.
/// @return 0 on success, negative on error (-3: shutdown join timed out, reported as ERROR)
int32_t arti_stop_generation(uint64_t generation);

#ifdef __cplusplus
}
#endif

#endif // ARTI_MACOS_H
