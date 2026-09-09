package io.github.ccbitz.mcsrcmcp.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class WorkspaceCache(private val maxWarm: Int = 3) {
    private val lock = ReentrantLock()
    private val order = ArrayDeque<String>()
    private val entries = ConcurrentHashMap<String, VersionWorkspace>()

    fun get(versionId: String): VersionWorkspace? = entries[versionId]

    fun put(versionId: String, workspace: VersionWorkspace) {
        lock.withLock {
            entries[versionId] = workspace
            order.remove(versionId)
            order.addLast(versionId)
            while (order.size > maxWarm) {
                val evicted = order.removeFirst()
                entries.remove(evicted)
            }
        }
    }

    fun warmVersions(): List<String> = lock.withLock { order.toList() }

    fun remove(versionId: String) {
        lock.withLock {
            entries.remove(versionId)
            order.remove(versionId)
        }
    }

    fun clear() {
        lock.withLock {
            entries.clear()
            order.clear()
        }
    }
}
