@file:OptIn(ExperimentalNativeApi::class)

import a2ui.AgentApp
import a2ui.COLORS
import a2ui.Http
import kotlinx.cinterop.toKString
import platform.posix.getenv
import ui.Trace
import ui.runApp
import kotlin.experimental.ExperimentalNativeApi

/**
 * The app's entry point, called from the backend's main() (core/hal/ec_hal.h).
 * A2UI_LLM: the llama.cpp server (host:port, an IP address), A2UI_PORT: the link port.
 */
@CName("ec_app_main")
fun kotlinMain(): Int {
    Trace.enabled = getenv("EC_TRACE")?.toKString() == "1"
    val llm = getenv("A2UI_LLM")?.toKString() ?: "127.0.0.1:8080"
    val port = getenv("A2UI_PORT")?.toKString()?.toIntOrNull() ?: 8082
    val http = Http(llm.substringBefore(':'), llm.substringAfter(':', "8080").toIntOrNull() ?: 8080)
    return runApp(background = COLORS.surface) { AgentApp(http, port) }
}
