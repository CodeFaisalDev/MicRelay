package com.micrelay.core.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-Free Multi-Consumer Circular Audio Ring Buffer.
 * Decouples the real-time AudioRecord hardware thread from network socket I/O
 * and local AAC disk encoding.
 */
class AudioRingBuffer(capacitySamples: Int = 48000 * 2) { // 2 seconds of 48kHz mono
    private val buffer = ShortArray(capacitySamples)
    private val capacity = capacitySamples
    private val writeCursor = AtomicLong(0)

    /**
     * Written exclusively by the high-priority AudioRecord capture thread.
     */
    fun write(samples: ShortArray, offset: Int, count: Int) {
        val currentWrite = writeCursor.get()
        for (i in 0 until count) {
            val idx = ((currentWrite + i) % capacity).toInt()
            buffer[idx] = samples[offset + i]
        }
        writeCursor.addAndGet(count.toLong())
    }

    /**
     * Independent consumer reader (e.g. Network Streamer or Local AAC Encoder).
     * @param cursor Current read position of the consumer (maintained by consumer).
     * @param dest Target array to populate.
     * @return Number of samples read and the updated consumer cursor.
     */
    fun read(cursor: Long, dest: ShortArray, maxSamples: Int): Pair<Int, Long> {
        val currentWrite = writeCursor.get()
        val available = currentWrite - cursor
        
        if (available <= 0) {
            return Pair(0, cursor)
        }

        // Detect if consumer fell too far behind (overflow protection)
        val actualCursor = if (available > capacity) {
            currentWrite - capacity
        } else {
            cursor
        }

        val toRead = Math.min(maxSamples.toLong(), currentWrite - actualCursor).toInt()
        for (i in 0 until toRead) {
            val idx = ((actualCursor + i) % capacity).toInt()
            dest[i] = buffer[idx]
        }

        return Pair(toRead, actualCursor + toRead)
    }

    fun getHead(): Long = writeCursor.get()
}
