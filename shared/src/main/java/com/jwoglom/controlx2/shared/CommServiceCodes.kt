package com.jwoglom.controlx2.shared

enum class CommServiceCodes {
    INIT_PUMP_COMM,
    CHECK_PUMP_CONNECTED,
    SEND_PUMP_PAIRING_MESSAGE,
    SEND_PUMP_COMMAND,
    SEND_PUMP_COMMANDS_BULK,
    SEND_PUMP_COMMANDS_BUST_CACHE_BULK,
    SEND_PUMP_COMMAND_BOLUS,
    CACHED_PUMP_COMMANDS_BULK,
    DEBUG_GET_MESSAGE_CACHE,
    DEBUG_GET_HISTORYLOG_CACHE,
    DEBUG_WRITE_BT_CHARACTERISTIC,
    WRITE_CHARACTERISTIC_FAILED_CALLBACK,
    STOP_PUMP_COMM,

    INIT_PUMP_FINDER_COMM,
    STOP_PUMP_FINDER_COMM,
    CHECK_PUMP_FINDER_FOUND_PUMPS,

    // Appended at the end: phone/watch builds exchange these by ordinal,
    // so new entries must not be inserted above existing ones.
    DEBUG_CLEAR_HISTORYLOG_CACHE,

    // Debug-only raw command delivery: bypasses the isBolusCommand send-layer
    // filter. Signature, encryption and connection checks are still enforced
    // inside PumpSession/Pump.command(). Gate this at the HTTP layer with the
    // `unsafe-raw-commands` preference.
    SEND_PUMP_COMMAND_RAW_UNSAFE,

    // 用户在「30 分钟无法连接」提示后点『继续』：重新挂起发现链并重新计时。
    RESTART_PUMP_RECONNECT,
}