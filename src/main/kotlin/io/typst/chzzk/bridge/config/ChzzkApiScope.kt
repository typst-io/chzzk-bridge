package io.typst.chzzk.bridge.config

enum class ChzzkApiScope(val label: String, val description: String) {
    CHAT("채팅 메시지 조회", "채팅 메시지"),
    DONATION("후원 조회", "후원 메시지"),
    USER_QUERY("유저 조회", "채널 ID"),
}