package com.yugentech.quill.aira.rag

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One shared structure per file revision. Publications remain owned by their reader/extractor. */
class BookStructureStore {
    private data class Key(val book: String, val path: String, val size: Long, val modified: Long)
    private class Entry(val key: Key) {
        val loading = Mutex()
        var structure: PublicationStructure? = null
        var pins = 0
        var valid = true
        val jobs = mutableSetOf<Job>()
    }
    private val entries = linkedMapOf<Key, Entry>()
    private val loads = java.util.concurrent.atomic.AtomicInteger()
    data class Diagnostics(val entries: Int, val pins: Int, val loads: Int)
    fun diagnostics() = synchronized(entries) { Diagnostics(entries.size, entries.values.sumOf { it.pins }, loads.get()) }
    private val changed = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val invalidations = changed.asSharedFlow()
    inner class Lease internal constructor(val structure: PublicationStructure, private val release: () -> Unit) : Closeable {
        private var closed = false
        override fun close() = synchronized(this) { if (!closed) { closed = true; release() } }
    }
    suspend fun acquire(bookId: String, path: String, loader: suspend () -> PublicationStructure): Lease {
        val key = withContext(Dispatchers.IO) { File(path).let { Key(bookId, it.canonicalPath, it.length(), it.lastModified()) } }
        val job = currentCoroutineContext()[Job]
        val entry = synchronized(entries) {
            entries.values.filter { it.key.book == bookId && it.key != key }.toList().forEach { invalidateEntry(it) }
            entries.getOrPut(key) { Entry(key) }.also { it.pins++; job?.let(it.jobs::add) }
        }
        try {
            val structure = entry.loading.withLock {
                currentCoroutineContext().ensureActive()
                check(entry.valid) { "Book text changed. Reopen the book." }
                entry.structure ?: loader().also { loaded ->
                    loads.incrementAndGet()
                    synchronized(entries) { check(entry.valid) { "Book text changed. Reopen the book." }; entry.structure = loaded }
                }
            }
            return Lease(structure) { release(entry, job) }
        } catch (error: Throwable) { release(entry, job); throw error }
    }
    private fun release(entry: Entry, job: Job?) = synchronized(entries) {
        entry.pins--; entry.jobs.remove(job)
        if (!entry.valid || entry.structure == null) entries.remove(entry.key, entry)
        val idle = entries.values.filter { it.pins == 0 }
        idle.dropLast(1).forEach { entries.remove(it.key) }
    }
    private fun invalidateEntry(entry: Entry) {
        entry.valid = false
        entry.jobs.toList().forEach { it.cancel(CancellationException("Book text changed")) }
        entries.remove(entry.key)
    }
    fun invalidate(bookId: String) {
        synchronized(entries) { entries.values.filter { it.key.book == bookId }.toList().forEach(::invalidateEntry) }
        changed.tryEmit(bookId)
    }
    fun invalidateAll() {
        val books = synchronized(entries) { entries.values.map { it.key.book }.distinct().also { entries.values.toList().forEach(::invalidateEntry) } }
        books.forEach(changed::tryEmit)
    }
    fun trimMemory() = synchronized(entries) { entries.values.filter { it.pins == 0 }.toList().forEach { entries.remove(it.key) }; Unit }
    internal fun cachedCount() = synchronized(entries) { entries.size }
}
