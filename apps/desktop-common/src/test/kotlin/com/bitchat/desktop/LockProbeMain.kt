package com.bitchat.desktop

import java.nio.file.Path
import kotlin.system.exitProcess

object LockProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val lock = SingleInstanceLock.tryAcquire(Path.of(args.single())) ?: exitProcess(1)
        lock.close()
    }
}
