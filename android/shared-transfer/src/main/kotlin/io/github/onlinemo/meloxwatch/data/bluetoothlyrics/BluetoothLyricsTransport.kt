package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import kotlinx.coroutines.Deferred

/** The coordinator tests exercise admission/retirement without creating a real Android socket. */
internal interface BluetoothLyricsTransport {
    val physicallyRetired: Boolean
    val closed: Boolean
    val authenticated: Boolean
    val comparison: BluetoothLyricsComparison?
    val peerLabel: String
    val closureIssue: BluetoothLyricsIssue?
    suspend fun openWatch(target: BluetoothLyricsTarget)
    suspend fun acceptPhone()
    suspend fun confirm(remainingMs: Long)
    suspend fun write(value: BluetoothLyricsPayload)
    suspend fun heartbeat()
    suspend fun read(): BluetoothLyricsMessage
    suspend fun awaitClosing(): BluetoothLyricsIssue
    fun close(issue: BluetoothLyricsIssue = BluetoothLyricsIssue.CLOSED): Deferred<Unit>
    suspend fun awaitClosed(waitMs: Long = 2500L)
    suspend fun retryRetirement(waitMs: Long = 2500L)
}
