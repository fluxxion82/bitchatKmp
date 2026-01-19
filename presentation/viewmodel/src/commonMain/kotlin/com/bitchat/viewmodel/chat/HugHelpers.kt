package com.bitchat.viewmodel.chat

fun buildHugActionMessage(input: String, senderNickname: String): String? {
    val targetRaw = input.removePrefix("/hug").trim()
    if (targetRaw.isBlank()) return null

    val target = targetRaw.removePrefix("@").trim()
    if (target.isBlank()) return null

    return "* $senderNickname gives $target a warm hug 🫂 *"
}
