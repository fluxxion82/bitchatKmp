/*
 * Stub implementations for systemd socket activation functions.
 * These are required by libdbus when built with systemd support,
 * but we don't use socket activation, so we can safely stub them.
 */

/* sd_listen_fds - Return number of file descriptors passed by systemd
 * We return 0 because we're not launched via systemd socket activation.
 */
int sd_listen_fds(int unset_environment) {
    (void)unset_environment;
    return 0;
}

/* sd_is_socket - Check if fd is a socket with given properties
 * We return 0 (no match) since we have no systemd-passed sockets.
 */
int sd_is_socket(int fd, int family, int type, int listening) {
    (void)fd;
    (void)family;
    (void)type;
    (void)listening;
    return 0;
}

/* fcntl64 - File control operations (64-bit version)
 * On arm64 Linux, fcntl and fcntl64 should be the same.
 * This stub forwards to the syscall wrapper.
 */
#define _GNU_SOURCE
#include <unistd.h>
#include <sys/syscall.h>

int fcntl64(int fd, int cmd, ...) {
    /* Use syscall directly to avoid recursion issues */
    __builtin_va_list args;
    __builtin_va_start(args, cmd);
    long arg = __builtin_va_arg(args, long);
    __builtin_va_end(args);
    return syscall(SYS_fcntl, fd, cmd, arg);
}
