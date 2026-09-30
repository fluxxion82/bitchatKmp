package com.bitchat.tui

import com.jakewharton.mosaic.modifier.Modifier

/**
 * Marks this node as a touch or click target that runs [onTap]. Screens use this seam rather than
 * any pointer API, so whatever backs it later (pointer modifiers in the Mosaic fork, plan Phase 4,
 * Route B) slots in without touching them.
 *
 * For now it is a no-op: no pointer event reaches a Mosaic composition yet. Every action a tap
 * would trigger must therefore also have a key binding in its screen.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.tap(onTap: () -> Unit): Modifier = this
