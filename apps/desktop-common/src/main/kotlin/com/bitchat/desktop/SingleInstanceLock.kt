package com.bitchat.desktop

import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import kotlin.system.exitProcess

class SingleInstanceLock private constructor(
    private val path: Path,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        lock.release()
        locks.remove(path, lock)
    }

    companion object {
        private val channels = mutableMapOf<Path, FileChannel>()
        private val locks = mutableMapOf<Path, FileLock>()

        fun tryAcquire(directory: Path = defaultDirectory()): SingleInstanceLock? = synchronized(channels) {
            val path = directory.toAbsolutePath().normalize()
            prepareDirectory(path)
            val channel = channels.getOrPut(path) {
                FileChannel.open(path.resolve(LOCK_FILE), CREATE, WRITE)
            }
            try {
                channel.tryLock()?.let { lock ->
                    locks[path] = lock
                    SingleInstanceLock(path, lock)
                }
            } catch (_: OverlappingFileLockException) {
                null
            }
        }

        fun acquireOrExit(
            directory: Path = defaultDirectory(),
            stderr: PrintStream = System.err,
            exit: (Int) -> Unit = ::exitProcess,
        ): SingleInstanceLock? {
            val lock = tryAcquire(directory)
            if (lock == null) {
                stderr.println("another bitchat desktop app is running")
                exit(1)
            }
            return lock
        }

        private fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".bitchat")

        private fun prepareDirectory(directory: Path) {
            Files.createDirectories(directory)
            try {
                Files.setPosixFilePermissions(
                    directory,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            } catch (_: UnsupportedOperationException) {
            }
        }

        private const val LOCK_FILE = "desktop.lock"
    }
}
