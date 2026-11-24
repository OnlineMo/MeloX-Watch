package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

/** Revocation is synchronous; completion waits for this exact external reading's retired work. */
interface BluetoothLyricsSource : AutoCloseable {
    suspend fun awaitClosed()
}
