package com.bitchat.bluetooth.protocol

/**
 * The largest reassembled frame accepted from a peer, the same on every platform. Upstream's
 * clients cap fragment reassembly at the same 1 MiB; what this app sends is far below it (images
 * are compressed to 100 KiB). A transfer remains eligible while chunks keep arriving; the idle
 * timeout is not a deadline for a full-size frame.
 */
const val MAX_MESH_FRAME_BYTES: Int = 1024 * 1024
