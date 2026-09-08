package io.github.belzenn.androidlinuxbridge.connection

internal class ReconnectPolicy {
    private var delayMs = 5_000L
    fun nextDelayMs(): Long = delayMs.also { delayMs = (delayMs * 2).coerceAtMost(60_000L) }
    fun reset() { delayMs = 5_000L }
}
