package com.bitchat.bluetooth.protocol

/**
 * The largest reassembled frame accepted from a peer, the same on every platform. Upstream's
 * clients cap fragment reassembly at the same 1 MiB; what this app sends is far below it (images
 * are compressed to 100 KiB). A transfer remains eligible while chunks keep arriving; the idle
 * timeout is not a deadline for a full-size frame.
 */
const val MAX_MESH_FRAME_BYTES: Int = 1024 * 1024

/** Fixed lanes stop claimed sender IDs from creating a coroutine and queue each. */
const val MESH_PACKET_LANES = 8
/** A full lane drops its newest packet, bounding queued work per lane. */
const val MESH_LANE_CAPACITY = 64
/** A largest frame always fits behind another in its lane; worst case: 8 lanes x 2 MiB. */
const val MAX_QUEUED_MESH_BYTES_PER_LANE = 2 * MAX_MESH_FRAME_BYTES

/** A full duplicate table forgets the oldest key: a late encrypted duplicate is replay-refused,
 * while a public one can be shown twice. */
const val MAX_PROCESSED_MESSAGE_IDS = 4096
/** The smaller ANNOUNCE table has the same early-forget cost: a late public duplicate can show twice. */
const val MAX_RECENT_ANNOUNCEMENTS = 1024

/** Pending undecryptable data is bounded per claimed peer until its handshake finishes. */
const val MAX_PENDING_PAYLOADS_PER_PEER = 4
/** 128 KiB covers text, voice notes and this app's 100 KiB compressed images; a larger file that
 * overtakes its own handshake is not kept. */
const val MAX_PENDING_BYTES_PER_PEER = 128 * 1024
/** At most 128 peers can hold pending data: 128 peers x 128 KiB is 16 MiB for 30 seconds. */
const val MAX_PENDING_PEERS = 128
/** A payload waiting for a handshake expires rather than surviving a lost exchange. */
const val PENDING_PAYLOAD_MAX_AGE_MS = 30_000L
