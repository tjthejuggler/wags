package com.example.wags.data.ble

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class CircularBuffer<T>(val capacity: Int) {
    private val buffer: Array<Any?> = arrayOfNulls(capacity)
    private val lock = ReentrantReadWriteLock()
    private var writeIdx = 0
    private var readIdx = 0
    private var count = 0

    /** Write a single value. Overwrites oldest if full. */
    fun write(value: T) {
        lock.write {
            buffer[writeIdx] = value
            writeIdx = (writeIdx + 1) % capacity
            if (count < capacity) count++ else readIdx = (readIdx + 1) % capacity
        }
    }

    /** Batch write — efficient for ECG packets (13 samples per BLE packet). */
    fun writeBatch(values: List<T>) {
        lock.write {
            for (value in values) {
                buffer[writeIdx] = value
                writeIdx = (writeIdx + 1) % capacity
                if (count < capacity) count++ else readIdx = (readIdx + 1) % capacity
            }
        }
    }

    /** Non-destructive: returns the last [n] written values (or fewer if buffer has less). */
    @Suppress("UNCHECKED_CAST")
    fun readLast(n: Int): List<T> {
        return lock.read {
            val available = minOf(n, count)
            if (available == 0) return@read emptyList()
            val result = ArrayList<T>(available)
            val startIdx = ((writeIdx - available) + capacity) % capacity
            for (i in 0 until available) {
                result.add(buffer[(startIdx + i) % capacity] as T)
            }
            result
        }
    }

    /** Destructive: advances read pointer by [n]. Returns consumed values. */
    @Suppress("UNCHECKED_CAST")
    fun consume(n: Int): List<T> {
        return lock.write {
            val available = minOf(n, count)
            if (available == 0) return@write emptyList()
            val result = ArrayList<T>(available)
            for (i in 0 until available) {
                result.add(buffer[readIdx] as T)
                readIdx = (readIdx + 1) % capacity
            }
            count -= available
            result
        }
    }

    fun size(): Int = lock.read { count }
    fun isFull(): Boolean = lock.read { count == capacity }
    fun clear() = lock.write { writeIdx = 0; readIdx = 0; count = 0 }
}
