package com.bitchat.local.prefs

import com.bitchat.domain.tor.model.TorMode

/**
 * Desktop starts with Tor off.
 *
 * Defaulting to ON here produced an app with no way out: the native library does not exist on this
 * platform, so the start failed, the switch rendered unchecked *and* disabled, and no code path
 * could express OFF. Clearing application data did not help either, because the default is
 * returned rather than stored, so a fresh state reproduced it exactly.
 */
internal actual val platformDefaultTorMode: TorMode = TorMode.OFF
