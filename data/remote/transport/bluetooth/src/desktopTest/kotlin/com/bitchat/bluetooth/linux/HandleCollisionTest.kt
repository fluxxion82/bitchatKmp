package com.bitchat.bluetooth.linux

import org.freedesktop.dbus.exceptions.DBusExecutionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The handle ladder must advance on a taken range and on nothing else.
 *
 * Both halves matter. Failing to advance leaves the peripheral role silently absent while the
 * central role keeps working -- a confusing partial outage. Advancing on an unrelated fault lets a
 * later base succeed and bury the original defect for good.
 */
class HandleCollisionTest {

    /**
     * The regression this test exists for.
     *
     * dbus-java maps an error name onto a generated class only when it recognises it; otherwise it
     * throws a bare [DBusExecutionException] whose `type` is that class's own name. So BlueZ's
     * `org.bluez.Error.Failed` arrives with a type of
     * "org.freedesktop.dbus.exceptions.DBusExecutionException", and a classifier that compares the
     * name against "org.bluez.Error.Failed" never fires.
     */
    @Test
    fun `a bare DBusExecutionException carrying the gatt-db text is a collision`() {
        val bare = DBusExecutionException("Failed to create entry in database")
        assertTrue(
            isHandleCollision(bare),
            "BlueZ reports a taken handle range as a bare DBusExecutionException whose type is the " +
                "class name, not org.bluez.Error.Failed. Matching on the name misses every real " +
                "collision and the ladder never advances."
        )
    }

    @Test
    fun `the 5_74 handle regression text is a collision`() {
        assertTrue(isHandleCollision(DBusExecutionException("Failed to create entry in database")))
    }

    @Test
    fun `an unrelated BlueZ failure is not a collision`() {
        assertFalse(
            isHandleCollision(DBusExecutionException("br-connection-profile-unavailable")),
            "advancing the ladder on an unrelated failure hides the real fault behind a later success"
        )
    }

    @Test
    fun `a local exception is never a collision`() {
        assertFalse(isHandleCollision(IllegalStateException("Failed to create entry in database")))
        assertFalse(isHandleCollision(RuntimeException("database")))
    }

    @Test
    fun `an already-registered path is not a collision`() {
        assertFalse(
            isHandleCollision(AlreadyExistsStub("Already Exists")),
            "AlreadyExists means BlueZ holds our path already; a different base would not help"
        )
    }

    @Test
    fun `a missing message is not a collision`() {
        assertFalse(isHandleCollision(DBusExecutionException(null)))
    }

    private class AlreadyExistsStub(message: String) : DBusExecutionException(message) {
        override fun getType(): String = "org.bluez.Error.AlreadyExists"
    }
}
