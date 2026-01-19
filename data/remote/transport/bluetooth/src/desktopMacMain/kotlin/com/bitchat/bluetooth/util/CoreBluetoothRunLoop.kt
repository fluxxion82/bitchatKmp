package com.bitchat.bluetooth.util

import kotlinx.cinterop.autoreleasepool
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSPort
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.run

object CoreBluetoothRunLoop {
    fun ensure() {
        if (NSThread.isMainThread) return
        val thread = NSThread {
            autoreleasepool {
                val runLoop = NSRunLoop.currentRunLoop
                val port = NSPort.port()
                runLoop.addPort(port, NSDefaultRunLoopMode)
                runLoop.run()
            }
        }
        thread.name = "CoreBluetoothMain"
        thread.start()
    }
}
