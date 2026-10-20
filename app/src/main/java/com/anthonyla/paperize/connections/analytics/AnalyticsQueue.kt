package com.anthonyla.paperize.connections.analytics

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Messages waiting to be sent: one JSON message per line in app-private storage, kept across restarts. */
internal class AnalyticsQueue(private val file: File) {
    private val mutex = Mutex()
    private val lines = ArrayDeque<String>()

    suspend fun load() {
        mutex.withLock {
            lines.clear()
            withContext(Dispatchers.IO) {
                if (file.exists()) file.useLines { seq -> seq.filter { it.isNotBlank() }.forEach { lines.addLast(it) } }
            }
            while (lines.size > AnalyticsConfig.MAX_QUEUED_MESSAGES) lines.removeFirst()
        }
    }

    /** Appends a message; past the cap the oldest messages are dropped. */
    suspend fun add(message: String) {
        mutex.withLock {
            lines.addLast(message)
            val overflow = lines.size > AnalyticsConfig.MAX_QUEUED_MESSAGES
            while (lines.size > AnalyticsConfig.MAX_QUEUED_MESSAGES) lines.removeFirst()
            withContext(Dispatchers.IO) {
                if (overflow) rewrite() else {
                    file.parentFile?.mkdirs()
                    file.appendText(message + "\n")
                }
            }
        }
    }

    /** The oldest messages that fit one request. They stay queued until [drop] confirms the send. */
    suspend fun nextBatch(): List<String> = mutex.withLock {
        val batch = mutableListOf<String>()
        var bytes = 0
        for (line in lines) {
            if (batch.size >= AnalyticsConfig.MAX_MESSAGES_PER_REQUEST) break
            val size = line.toByteArray(Charsets.UTF_8).size
            if (batch.isNotEmpty() && bytes + size + 1024 > AnalyticsConfig.MAX_BATCH_BYTES) break
            batch += line
            bytes += size
        }
        batch
    }

    suspend fun drop(count: Int) {
        mutex.withLock {
            repeat(count) { if (lines.isNotEmpty()) lines.removeFirst() }
            withContext(Dispatchers.IO) { rewrite() }
        }
    }

    private fun rewrite() {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(lines.joinToString("") { it + "\n" })
        temp.renameTo(file)
    }
}
