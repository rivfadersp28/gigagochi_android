package com.gigagochi.app.core.webview

import com.gigagochi.app.core.network.StaticImageMaxBytes
import com.gigagochi.app.core.network.StaticMediaDiskCacheBytes
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.core.network.StaticVideoMaxBytes
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class WebMediaKind(
    val maxResourceBytes: Long,
) {
    Video(StaticVideoMaxBytes),
    Image(StaticImageMaxBytes),
}

/** Current-screen media may displace history; cold history is admitted through a fixed window. */
internal enum class WebMediaProjectionPriority {
    Current,
    History,
}

internal data class WebMediaOwnerScope(
    val ownerId: String,
    val petId: String,
) {
    init {
        require(ownerId.isNotBlank() && petId.isNotBlank())
    }
}

/** Metadata available to the Web handler only after the local file has been fully verified. */
internal data class RegisteredWebMedia(
    val ownerId: String,
    val petId: String,
    val path: File,
    val mimeType: String,
    val version: String,
    val byteLength: Long,
    val maxResourceBytes: Long,
)

internal class ResolvedWebMedia internal constructor(
    val media: RegisteredWebMedia,
    internal val documentToken: String,
    internal val resourceToken: String,
    internal val documentGeneration: Long,
)

internal data class WebMediaPublicationIdentity(
    val scope: WebMediaOwnerScope,
    val kind: WebMediaKind,
    val slot: String?,
    val sourceKey: String,
    val resourceToken: String,
)

/** Contains no backend URL. Runtime must re-check this fence before emitting stateChanged. */
internal data class WebMediaPublication(
    val documentGeneration: Long,
    val identities: List<WebMediaPublicationIdentity>,
)

internal fun interface WebMediaPublicationListener {
    fun onPublished(publication: WebMediaPublication)
}

internal data class WebMediaRegistryStats(
    val entryCount: Int,
    val uniqueLeasedBytes: Long,
    val inFlightCount: Int,
    val admittedColdHistoryCount: Int,
    val negativeSourceCount: Int,
)

private data class MediaWorkKey(
    val sourceKey: String,
    val kind: WebMediaKind,
)

private data class PendingWebMedia(
    val work: MediaWorkKey,
    val scope: WebMediaOwnerScope,
    val slot: String?,
) {
    val kind: WebMediaKind
        get() = work.kind
}

private data class DesiredWebMedia(
    val pending: PendingWebMedia,
    val sourceUrl: String,
    val priority: WebMediaProjectionPriority,
    val documentGeneration: Long,
    val sequence: Long,
)

private class SharedMediaLease(
    val work: MediaWorkKey,
    val asset: VerifiedWebMediaAsset,
    var referenceCount: Int = 0,
)

private data class RegistryEntry(
    val pending: PendingWebMedia,
    val lease: SharedMediaLease,
    val priority: WebMediaProjectionPriority,
)

private data class MediaSlot(
    val scope: WebMediaOwnerScope,
    val kind: WebMediaKind,
    val value: String,
)

private data class NegativeMaterialization(
    val attempts: Int,
    val retryAtMillis: Long,
)

private data class ProjectionCycle(
    val slots: MutableSet<MediaSlot> = mutableSetOf(),
    val unslotted: MutableSet<PendingWebMedia> = mutableSetOf(),
    val historyByRecency: LinkedHashSet<MediaWorkKey> = linkedSetOf(),
)

private data class Installation(
    val identity: WebMediaPublicationIdentity,
    val consumedAcquiredLease: Boolean,
)

/**
 * A document-scoped capability registry for complete, verified local media.
 *
 * Snapshot projection performs only URL-policy checks, small verified-sidecar cache lookups and
 * in-memory capability work. Cache misses are deduplicated and materialized on the injected IO
 * dispatcher. Raw and partial URLs are never published. Failed slot replacements retain the last
 * verified capability, while document/scope/source fences discard stale completions.
 */
internal class WebMediaReferenceRegistry(
    internal val urlPolicy: StaticMediaUrlPolicy,
    private val tokenFactory: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
    private val materializer: WebMediaMaterializer = UnavailableMediaMaterializer,
    private val scopeProvider: () -> WebMediaOwnerScope? = { UnscopedTestMediaOwner },
    materializationScope: CoroutineScope? = null,
    private val materializationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxEntries: Int = DefaultMaxEntries,
    private val maxLeasedBytes: Long = DefaultMaxLeasedBytes,
    private val maxInFlight: Int = DefaultMaxInFlight,
    private val maxColdHistorySources: Int = DefaultMaxColdHistorySources,
    private val initialBackoffMillis: Long = DefaultInitialBackoffMillis,
    private val maxBackoffMillis: Long = DefaultMaxBackoffMillis,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private val ownedScope = materializationScope == null
    private val backgroundScope = materializationScope ?: CoroutineScope(SupervisorJob())
    @Volatile
    private var publicationListener = WebMediaPublicationListener { }
    private var documentToken = nextOpaqueToken()
    private var documentGeneration = 0L
    private var desiredSequence = 0L
    private var closed = false
    private var activeProjection: ProjectionCycle? = null
    private var uniqueLeasedBytes = 0L
    private val entries = linkedMapOf<String, RegistryEntry>()
    private val referencesByMedia = mutableMapOf<PendingWebMedia, String>()
    private val resourcesBySlot = mutableMapOf<MediaSlot, String>()
    private val desiredBySlot = mutableMapOf<MediaSlot, DesiredWebMedia>()
    private val desiredUnslotted = mutableMapOf<PendingWebMedia, DesiredWebMedia>()
    private val leasesBySource = mutableMapOf<MediaWorkKey, SharedMediaLease>()
    private val cacheChecks = mutableSetOf<MediaWorkKey>()
    private val cacheMisses = mutableSetOf<MediaWorkKey>()
    private val inFlight = mutableMapOf<MediaWorkKey, Job>()
    private val inFlightPriorities = mutableMapOf<MediaWorkKey, WebMediaProjectionPriority>()
    private var retryWakeJob: Job? = null
    private var retryWakeAtMillis: Long? = null
    private val negativeSources = mutableMapOf<MediaWorkKey, NegativeMaterialization>()
    private val capacityRejectedSources = mutableSetOf<MediaWorkKey>()
    private val recentHistorySources = linkedSetOf<MediaWorkKey>()

    init {
        require(maxEntries > 0)
        require(maxLeasedBytes > 0L)
        require(maxInFlight > 0)
        require(maxColdHistorySources >= 0)
        require(initialBackoffMillis > 0L && maxBackoffMillis >= initialBackoffMillis)
    }

    @Synchronized
    fun setPublicationListener(listener: WebMediaPublicationListener) {
        publicationListener = listener
    }

    /** Removes media no longer present in one authoritative native snapshot projection. */
    fun <T> projectSnapshot(block: () -> T): T {
        synchronized(this) {
            check(activeProjection == null) { "Nested media projection is not supported" }
            activeProjection = ProjectionCycle()
        }
        return try {
            block()
        } finally {
            synchronized(this) {
                finishProjectionLocked()
            }
        }
    }

    @Synchronized
    fun invalidateDocument() {
        val previous = documentToken
        documentToken = (1..8)
            .asSequence()
            .map { nextOpaqueToken() }
            .firstOrNull { it != previous }
            ?: error("Could not rotate the media document capability")
        documentGeneration = Math.addExact(documentGeneration, 1L)
        entries.keys.toList().forEach(::revokeLocked)
        desiredBySlot.clear()
        desiredUnslotted.clear()
        recentHistorySources.clear()
        capacityRejectedSources.clear()
        cacheMisses.clear()
        negativeSources.clear()
        inFlightPriorities.keys.forEach { work ->
            inFlightPriorities[work] = WebMediaProjectionPriority.History
        }
        retryWakeJob?.cancel()
        retryWakeJob = null
        retryWakeAtMillis = null
        activeProjection = null
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            entries.keys.toList().forEach(::revokeLocked)
            desiredBySlot.clear()
            desiredUnslotted.clear()
            cacheMisses.clear()
            negativeSources.clear()
            capacityRejectedSources.clear()
            retryWakeJob?.cancel()
            retryWakeJob = null
            retryWakeAtMillis = null
            activeProjection = null
        }
        if (ownedScope) backgroundScope.cancel()
    }

    /** A foreground transition is an explicit retry signal for currently desired failures. */
    @Synchronized
    fun retryFailedOnForeground() {
        val desiredWorks = currentDesiredLocked().mapTo(mutableSetOf()) { it.pending.work }
        negativeSources.keys.retainAll(desiredWorks)
        desiredWorks.forEach { work ->
            negativeSources[work]?.let { failure ->
                negativeSources[work] = failure.copy(retryAtMillis = 0L)
            }
        }
        scheduleEligibleLocked()
    }

    fun register(
        value: String?,
        kind: WebMediaKind,
        slot: String? = null,
        scope: WebMediaOwnerScope? = scopeProvider(),
        priority: WebMediaProjectionPriority = WebMediaProjectionPriority.Current,
    ): String? {
        val safeScope = scope ?: return null
        val safeSlot = slot?.also {
            require(it.isNotBlank() && it.length <= MaxSlotLength)
        }
        val slotKey = safeSlot?.let { MediaSlot(safeScope, kind, it) }
        val sourceUrl = urlPolicy.resolve(value)
            ?.takeIf { sourceExtensionMatchesKind(it, kind) }
            ?: return synchronized(this) {
                slotKey?.let { key ->
                    activeProjection?.slots?.add(key)
                    clearSlotLocked(key)
                }
                null
            }
        val pending = PendingWebMedia(
            work = MediaWorkKey(webMediaSourceKey(sourceUrl), kind),
            scope = safeScope,
            slot = safeSlot,
        )

        val generation: Long
        synchronized(this) {
            if (closed || !isScopeCurrent(safeScope)) return null
            val projectionAdmitted = noteDesiredLocked(pending, sourceUrl, priority)
            if (!projectionAdmitted) return null
            activeReferenceLocked(pending)?.let { return it }
            installSharedLeaseLocked(pending, priority)?.let { return it }
            val fallback = previousSlotReferenceLocked(pending)
            if (
                pending.work in cacheChecks ||
                pending.work in cacheMisses ||
                pending.work in inFlight ||
                pending.work in capacityRejectedSources
            ) {
                scheduleEligibleLocked()
                return fallback
            }
            cacheChecks += pending.work
            generation = documentGeneration
        }

        val cached = runCatching {
            materializer.acquireCached(sourceUrl, kind)
        }.getOrNull()
        return synchronized(this) {
            cacheChecks.remove(pending.work)
            if (
                cached == null &&
                !closed &&
                generation == documentGeneration &&
                isScopeCurrent(safeScope) &&
                isDesiredLocked(pending)
            ) {
                cacheMisses += pending.work
            }
            if (
                cached != null &&
                !closed &&
                generation == documentGeneration &&
                isScopeCurrent(safeScope) &&
                isDesiredLocked(pending)
            ) {
                val installed = installAcquiredLocked(pending, priority, cached)
                if (installed == null) {
                    capacityRejectedSources += pending.work
                    materializer.release(cached)
                } else if (!installed.consumedAcquiredLease) {
                    materializer.release(cached)
                }
            } else if (cached != null) {
                materializer.release(cached)
            }
            scheduleEligibleLocked()
            activeReferenceLocked(pending) ?: previousSlotReferenceLocked(pending)
        }
    }

    fun resolveSource(value: String?): String? = urlPolicy.resolve(value)

    @Synchronized
    fun resolveRequest(value: String): ResolvedWebMedia? {
        val resourceToken = parseResourceToken(value) ?: return null
        val entry = entries[resourceToken] ?: return null
        if (!isScopeCurrent(entry.pending.scope)) {
            revokeLocked(resourceToken)
            return null
        }
        val verified = entry.lease.asset
        if (!verified.file.isFile || verified.file.length() != verified.byteLength) {
            revokeLocked(resourceToken)
            return null
        }
        return resolved(verified, entry.pending, resourceToken)
    }

    @Synchronized
    fun isActive(resolved: ResolvedWebMedia): Boolean {
        if (
            resolved.documentToken != documentToken ||
            resolved.documentGeneration != documentGeneration
        ) {
            return false
        }
        val entry = entries[resolved.resourceToken] ?: return false
        if (!isScopeCurrent(entry.pending.scope)) return false
        val verified = entry.lease.asset
        return resolved.media.path == verified.file &&
            resolved.media.version == verified.version &&
            verified.file.isFile &&
            verified.file.length() == verified.byteLength
    }

    @Synchronized
    fun isPublicationCurrent(publication: WebMediaPublication): Boolean =
        publication.documentGeneration == documentGeneration &&
            publication.identities.any { identity ->
                if (!isScopeCurrent(identity.scope)) return@any false
                val entry = entries[identity.resourceToken] ?: return@any false
                entry.pending.scope == identity.scope &&
                    entry.pending.kind == identity.kind &&
                    entry.pending.slot == identity.slot &&
                    entry.pending.work.sourceKey == identity.sourceKey &&
                    isDesiredLocked(entry.pending)
            }

    @Synchronized
    fun stats(): WebMediaRegistryStats = WebMediaRegistryStats(
        entryCount = entries.size,
        uniqueLeasedBytes = uniqueLeasedBytes,
        inFlightCount = inFlight.size,
        admittedColdHistoryCount = recentHistorySources.size,
        negativeSourceCount = negativeSources.size,
    )

    @Synchronized
    private fun noteDesiredLocked(
        pending: PendingWebMedia,
        sourceUrl: String,
        priority: WebMediaProjectionPriority,
    ): Boolean {
        if (priority == WebMediaProjectionPriority.Current) {
            capacityRejectedSources.remove(pending.work)
            inFlightPriorities[pending.work]?.let { runningPriority ->
                inFlightPriorities[pending.work] = minOf(runningPriority, priority)
            }
        }
        val projectionAdmitted = if (priority == WebMediaProjectionPriority.History) {
            val projection = activeProjection
            if (projection != null) {
                val wasRecent = pending.work in recentHistorySources
                projection.historyByRecency += pending.work
                val admitted = projection.historyByRecency
                    .take(maxColdHistorySources)
                    .contains(pending.work)
                if (admitted) {
                    recentHistorySources += pending.work
                    if (!wasRecent) capacityRejectedSources.remove(pending.work)
                }
                admitted
            } else {
                if (
                    pending.work !in recentHistorySources &&
                    recentHistorySources.size < maxColdHistorySources
                ) {
                    recentHistorySources += pending.work
                }
                pending.work in recentHistorySources
            }
        } else {
            true
        }
        desiredSequence = Math.addExact(desiredSequence, 1L)
        val desired = DesiredWebMedia(
            pending = pending,
            sourceUrl = sourceUrl,
            priority = priority,
            documentGeneration = documentGeneration,
            sequence = desiredSequence,
        )
        pending.slot?.let { slot ->
            val key = MediaSlot(pending.scope, pending.kind, slot)
            val firstUseInProjection = activeProjection?.slots?.add(key) == true
            val current = desiredBySlot[key]
            desiredBySlot[key] = if (current?.pending == pending && !firstUseInProjection) {
                current.copy(
                    priority = minOf(current.priority, priority),
                    documentGeneration = documentGeneration,
                )
            } else {
                desired
            }
        } ?: run {
            val firstUseInProjection = activeProjection?.unslotted?.add(pending) == true
            val current = desiredUnslotted[pending]
            desiredUnslotted[pending] =
                if (current == null || firstUseInProjection) {
                    desired
                } else {
                    current.copy(
                        priority = minOf(current.priority, priority),
                        documentGeneration = documentGeneration,
                    )
                }
        }
        val effectivePriority = pending.slot?.let { slot ->
            desiredBySlot.getValue(MediaSlot(pending.scope, pending.kind, slot)).priority
        } ?: desiredUnslotted.getValue(pending).priority
        referencesByMedia[pending]?.let { token ->
            entries[token]?.let { entry ->
                if (effectivePriority != entry.priority) {
                    entries[token] = entry.copy(priority = effectivePriority)
                }
            }
        }
        return projectionAdmitted
    }

    @Synchronized
    private fun activeReferenceLocked(pending: PendingWebMedia): String? {
        val resource = referencesByMedia[pending] ?: return null
        val entry = entries[resource] ?: return null
        val asset = entry.lease.asset
        if (!asset.file.isFile || asset.file.length() != asset.byteLength) {
            revokeLocked(resource)
            return null
        }
        return reference(documentToken, resource)
    }

    @Synchronized
    private fun previousSlotReferenceLocked(pending: PendingWebMedia): String? {
        val slot = pending.slot ?: return null
        val key = MediaSlot(pending.scope, pending.kind, slot)
        val resource = resourcesBySlot[key] ?: return null
        if (resource !in entries) return null
        return reference(documentToken, resource)
    }

    @Synchronized
    private fun installSharedLeaseLocked(
        pending: PendingWebMedia,
        priority: WebMediaProjectionPriority,
    ): String? {
        val lease = leasesBySource[pending.work] ?: return null
        val installation = installEntryLocked(pending, priority, lease, acquired = false)
            ?: return null
        return reference(documentToken, installation.identity.resourceToken)
    }

    @Synchronized
    private fun installAcquiredLocked(
        pending: PendingWebMedia,
        priority: WebMediaProjectionPriority,
        acquired: VerifiedWebMediaAsset,
    ): Installation? {
        val existing = leasesBySource[pending.work]
        if (existing != null) {
            return installEntryLocked(pending, priority, existing, acquired = false)
        }
        if (!makeCapacityLocked(pending, priority, acquired.byteLength)) return null
        val lease = SharedMediaLease(pending.work, acquired)
        leasesBySource[pending.work] = lease
        uniqueLeasedBytes = Math.addExact(uniqueLeasedBytes, acquired.byteLength)
        val installation = runCatching {
            installEntryLocked(pending, priority, lease, acquired = true)
        }.getOrElse { failure ->
            leasesBySource.remove(pending.work)
            uniqueLeasedBytes -= acquired.byteLength
            materializer.release(acquired)
            throw failure
        }
        if (installation == null) {
            leasesBySource.remove(pending.work)
            uniqueLeasedBytes -= acquired.byteLength
        }
        return installation
    }

    @Synchronized
    private fun installEntryLocked(
        pending: PendingWebMedia,
        priority: WebMediaProjectionPriority,
        lease: SharedMediaLease,
        acquired: Boolean,
    ): Installation? {
        referencesByMedia[pending]?.let { existing ->
            return Installation(publicationIdentity(existing, entries.getValue(existing)), false)
        }
        if (!makeCapacityLocked(pending, priority, lease.asset.byteLength, lease)) return null
        val resourceToken = (1..8)
            .asSequence()
            .map { nextOpaqueToken() }
            .firstOrNull { candidate -> candidate !in entries }
            ?: error("Could not allocate a unique media capability")
        val entry = RegistryEntry(pending, lease, priority)
        entries[resourceToken] = entry
        referencesByMedia[pending] = resourceToken
        lease.referenceCount = Math.addExact(lease.referenceCount, 1)
        pending.slot?.let { slot ->
            val key = MediaSlot(pending.scope, pending.kind, slot)
            val replaced = resourcesBySlot.put(key, resourceToken)
            if (replaced != null && replaced != resourceToken) revokeLocked(replaced)
        }
        return Installation(publicationIdentity(resourceToken, entry), acquired)
    }

    @Synchronized
    private fun makeCapacityLocked(
        pending: PendingWebMedia,
        priority: WebMediaProjectionPriority,
        incomingBytes: Long,
        existingLease: SharedMediaLease? = leasesBySource[pending.work],
    ): Boolean {
        fun fits(): Boolean {
            val replacing = pending.slot?.let { slot ->
                resourcesBySlot[MediaSlot(pending.scope, pending.kind, slot)]
            }?.let(entries::get)
            val entryDelta = if (replacing == null) 1 else 0
            val byteDelta = if (existingLease == null) incomingBytes else 0L
            val releasedBytes = replacing
                ?.lease
                ?.takeIf { it !== existingLease && it.referenceCount == 1 }
                ?.asset
                ?.byteLength
                ?: 0L
            return entries.size + entryDelta <= maxEntries &&
                uniqueLeasedBytes + byteDelta - releasedBytes <= maxLeasedBytes
        }

        if (fits()) return true
        if (priority != WebMediaProjectionPriority.Current) return false
        while (!fits()) {
            val victim = entries.entries.firstOrNull { (_, entry) ->
                entry.priority == WebMediaProjectionPriority.History &&
                    entry.pending != pending
            } ?: return false
            capacityRejectedSources += victim.value.pending.work
            revokeLocked(victim.key)
        }
        return true
    }

    @Synchronized
    private fun revokeLocked(resourceToken: String) {
        val entry = entries.remove(resourceToken) ?: return
        referencesByMedia.remove(entry.pending)
        entry.pending.slot?.let { slot ->
            val key = MediaSlot(entry.pending.scope, entry.pending.kind, slot)
            if (resourcesBySlot[key] == resourceToken) resourcesBySlot.remove(key)
        }
        entry.lease.referenceCount -= 1
        if (entry.lease.referenceCount == 0) {
            leasesBySource.remove(entry.lease.work)
            uniqueLeasedBytes -= entry.lease.asset.byteLength
            materializer.release(entry.lease.asset)
        }
    }

    @Synchronized
    private fun clearSlotLocked(slot: MediaSlot) {
        desiredBySlot.remove(slot)
        resourcesBySlot[slot]?.let(::revokeLocked)
    }

    @Synchronized
    private fun finishProjectionLocked() {
        val projection = activeProjection ?: return
        val entriesBeforeCleanup = entries.size
        val leasedBytesBeforeCleanup = uniqueLeasedBytes
        desiredBySlot.keys.filterNot(projection.slots::contains).forEach(::clearSlotLocked)
        desiredUnslotted.keys.filterNot(projection.unslotted::contains).forEach { pending ->
            desiredUnslotted.remove(pending)
            referencesByMedia[pending]?.let(::revokeLocked)
        }
        val projectedRecent = projection.historyByRecency
            .take(maxColdHistorySources)
            .toSet()
        recentHistorySources.retainAll(projectedRecent)
        recentHistorySources += projectedRecent
        entries.entries
            .filter { (_, entry) ->
                entry.priority == WebMediaProjectionPriority.History &&
                    entry.pending.work !in recentHistorySources
            }
            .map(Map.Entry<String, RegistryEntry>::key)
            .forEach(::revokeLocked)
        if (entries.size < entriesBeforeCleanup || uniqueLeasedBytes < leasedBytesBeforeCleanup) {
            capacityRejectedSources.removeAll(projectedRecent)
        }
        val retainedAuxiliaryWorks = currentDesiredLocked()
            .asSequence()
            .filter { desired ->
                desired.priority == WebMediaProjectionPriority.Current ||
                    desired.pending.work in recentHistorySources
            }
            .mapTo(mutableSetOf()) { it.pending.work }
        cacheMisses.retainAll(retainedAuxiliaryWorks)
        negativeSources.keys.retainAll(retainedAuxiliaryWorks)
        capacityRejectedSources.retainAll(retainedAuxiliaryWorks)
        refreshInFlightPrioritiesLocked()
        activeProjection = null
        scheduleEligibleLocked()
    }

    @Synchronized
    private fun scheduleEligibleLocked() {
        // A projection may encounter History before a later opened-story Current registration.
        // Defer cold work until the authoritative cycle is complete so priority, not call order,
        // decides which sources occupy the bounded materialization lanes.
        if (closed) {
            cancelRetryWakeLocked()
            return
        }
        if (activeProjection != null) return
        while (true) {
            val usingCurrentPriorityLane = inFlight.size >= maxInFlight
            if (
                usingCurrentPriorityLane &&
                (
                    inFlight.size >= maxInFlight + CurrentPriorityLaneCount ||
                        inFlightPriorities.values.any {
                            it == WebMediaProjectionPriority.Current
                        } ||
                        inFlightPriorities.values.none {
                            it == WebMediaProjectionPriority.History
                        }
                    )
            ) {
                scheduleRetryWakeLocked()
                return
            }
            val now = nowMillis()
            val desired = currentDesiredLocked()
                .sortedWith(compareBy<DesiredWebMedia>({ it.priority }, { it.sequence }))
                .firstOrNull { candidate ->
                    if (
                        usingCurrentPriorityLane &&
                        candidate.priority != WebMediaProjectionPriority.Current
                    ) {
                        return@firstOrNull false
                    }
                    val work = candidate.pending.work
                    if (
                        work in inFlight ||
                        work in cacheChecks ||
                        work in capacityRejectedSources ||
                        work in leasesBySource
                    ) {
                        return@firstOrNull false
                    }
                    if (
                        candidate.priority == WebMediaProjectionPriority.History &&
                        work !in recentHistorySources
                    ) {
                        return@firstOrNull false
                    }
                    val negative = negativeSources[work]
                    if (negative != null && negative.retryAtMillis > now) return@firstOrNull false
                    val replacesSlot = candidate.pending.slot?.let { slot ->
                        MediaSlot(candidate.pending.scope, candidate.pending.kind, slot)
                    }?.let(resourcesBySlot::containsKey) == true
                    if (
                        candidate.priority == WebMediaProjectionPriority.History &&
                        entries.size >= maxEntries &&
                        !replacesSlot
                    ) {
                        capacityRejectedSources += work
                        return@firstOrNull false
                    }
                    true
                } ?: run {
                    scheduleRetryWakeLocked()
                    return
                }
            launchMaterializationLocked(desired)
        }
    }

    @Synchronized
    private fun refreshInFlightPrioritiesLocked() {
        val desired = currentDesiredLocked()
        inFlight.keys.forEach { work ->
            inFlightPriorities[work] = desired
                .asSequence()
                .filter { candidate -> candidate.pending.work == work }
                .map(DesiredWebMedia::priority)
                .minOrNull()
                ?: WebMediaProjectionPriority.History
        }
        inFlightPriorities.keys.retainAll(inFlight.keys)
    }

    @Synchronized
    private fun scheduleRetryWakeLocked() {
        if (closed || activeProjection != null) return
        val now = nowMillis()
        val earliest = currentDesiredLocked()
            .asSequence()
            .filter { desired ->
                desired.priority == WebMediaProjectionPriority.Current ||
                    desired.pending.work in recentHistorySources
            }
            .mapNotNull { desired ->
                val work = desired.pending.work
                if (
                    work in inFlight ||
                    work in cacheChecks ||
                    work in capacityRejectedSources ||
                    work in leasesBySource
                ) {
                    return@mapNotNull null
                }
                negativeSources[work]?.retryAtMillis?.takeIf { it > now }
            }
            .minOrNull()
        if (earliest == null) {
            cancelRetryWakeLocked()
            return
        }
        if (retryWakeAtMillis == earliest && retryWakeJob?.isActive == true) return
        cancelRetryWakeLocked()
        val waitMillis = (earliest - now).coerceAtLeast(1L)
        lateinit var scheduled: Job
        scheduled = backgroundScope.launch {
            retryDelayMillis(waitMillis)
            synchronized(this@WebMediaReferenceRegistry) {
                if (retryWakeJob !== scheduled) return@synchronized
                retryWakeJob = null
                retryWakeAtMillis = null
                scheduleEligibleLocked()
            }
        }
        retryWakeAtMillis = earliest
        retryWakeJob = scheduled
    }

    @Synchronized
    private fun cancelRetryWakeLocked() {
        retryWakeJob?.cancel()
        retryWakeJob = null
        retryWakeAtMillis = null
    }

    @Synchronized
    private fun launchMaterializationLocked(desired: DesiredWebMedia) {
        val work = desired.pending.work
        if (work in inFlight || closed) return
        lateinit var job: Job
        job = backgroundScope.launch(
            context = materializationDispatcher,
            start = CoroutineStart.LAZY,
        ) {
            val result = try {
                Result.success(materializer.acquire(desired.sourceUrl, work.kind))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            completeMaterialization(work, result)
        }
        inFlight[work] = job
        inFlightPriorities[work] = desired.priority
        job.invokeOnCompletion { failure ->
            if (failure is CancellationException) {
                synchronized(this) {
                    if (inFlight[work] === job) {
                        inFlight.remove(work)
                        inFlightPriorities.remove(work)
                        scheduleEligibleLocked()
                    }
                }
            }
        }
        job.start()
    }

    private fun completeMaterialization(
        work: MediaWorkKey,
        result: Result<VerifiedWebMediaAsset>,
    ) {
        var publication: WebMediaPublication? = null
        synchronized(this) {
            inFlight.remove(work)
            inFlightPriorities.remove(work)
            val acquired = result.getOrNull()
            if (acquired == null) {
                val stillEligible = currentDesiredLocked().any { desired ->
                    desired.pending.work == work &&
                        (
                            desired.priority == WebMediaProjectionPriority.Current ||
                                work in recentHistorySources
                            )
                }
                if (stillEligible) {
                    val previous = negativeSources[work]
                    val attempts = Math.addExact(previous?.attempts ?: 0, 1)
                    negativeSources[work] = NegativeMaterialization(
                        attempts = attempts,
                        retryAtMillis = saturatedAdd(nowMillis(), backoffMillis(attempts)),
                    )
                } else {
                    negativeSources.remove(work)
                }
            } else if (closed) {
                materializer.release(acquired)
            } else {
                negativeSources.remove(work)
                cacheMisses.remove(work)
                val desired = currentDesiredLocked()
                    .filter {
                        it.pending.work == work &&
                            isScopeCurrent(it.pending.scope) &&
                            (it.priority != WebMediaProjectionPriority.History ||
                                work in recentHistorySources)
                    }
                    .sortedWith(compareBy<DesiredWebMedia>({ it.priority }, { it.sequence }))
                val identities = mutableListOf<WebMediaPublicationIdentity>()
                var acquiredConsumed = false
                desired.forEach { target ->
                    if (activeReferenceLocked(target.pending) != null) return@forEach
                    val installation = if (acquiredConsumed) {
                        installSharedLeaseLocked(target.pending, target.priority)
                            ?.let { reference ->
                                val token = reference.substringAfterLast('/')
                                Installation(
                                    publicationIdentity(token, entries.getValue(token)),
                                    false,
                                )
                            }
                    } else {
                        installAcquiredLocked(target.pending, target.priority, acquired)
                    }
                    if (installation != null) {
                        acquiredConsumed = acquiredConsumed || installation.consumedAcquiredLease
                        identities += installation.identity
                    }
                }
                if (!acquiredConsumed) {
                    materializer.release(acquired)
                    if (desired.isNotEmpty()) capacityRejectedSources += work
                }
                if (identities.isNotEmpty()) {
                    publication = WebMediaPublication(documentGeneration, identities)
                }
            }
            scheduleEligibleLocked()
        }
        publication?.let { ready ->
            runCatching { publicationListener.onPublished(ready) }
        }
    }

    @Synchronized
    private fun currentDesiredLocked(): List<DesiredWebMedia> =
        (desiredBySlot.values + desiredUnslotted.values).filter { desired ->
            desired.documentGeneration == documentGeneration && isDesiredLocked(desired.pending)
        }

    @Synchronized
    private fun isDesiredLocked(pending: PendingWebMedia): Boolean = pending.slot?.let { slot ->
        desiredBySlot[MediaSlot(pending.scope, pending.kind, slot)]?.pending == pending
    } ?: (desiredUnslotted[pending]?.pending == pending)

    private fun isScopeCurrent(scope: WebMediaOwnerScope): Boolean = scopeProvider() == scope

    private fun publicationIdentity(
        resourceToken: String,
        entry: RegistryEntry,
    ): WebMediaPublicationIdentity = WebMediaPublicationIdentity(
        scope = entry.pending.scope,
        kind = entry.pending.kind,
        slot = entry.pending.slot,
        sourceKey = entry.pending.work.sourceKey,
        resourceToken = resourceToken,
    )

    private fun resolved(
        asset: VerifiedWebMediaAsset,
        pending: PendingWebMedia,
        resourceToken: String,
    ): ResolvedWebMedia = ResolvedWebMedia(
        media = RegisteredWebMedia(
            ownerId = pending.scope.ownerId,
            petId = pending.scope.petId,
            path = asset.file,
            mimeType = asset.mimeType,
            version = asset.version,
            byteLength = asset.byteLength,
            maxResourceBytes = pending.kind.maxResourceBytes,
        ),
        documentToken = documentToken,
        resourceToken = resourceToken,
        documentGeneration = documentGeneration,
    )

    @Synchronized
    private fun parseResourceToken(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (
            uri.scheme != "https" ||
            !uri.host.equals(WebMediaHost, ignoreCase = true) ||
            uri.port != -1 ||
            uri.userInfo != null ||
            uri.rawQuery != null ||
            uri.fragment != null
        ) {
            return null
        }
        val match = ReferencePath.matchEntire(uri.rawPath.orEmpty()) ?: return null
        if (match.groupValues[1] != documentToken) return null
        return match.groupValues[2]
    }

    private fun nextOpaqueToken(): String = tokenFactory().also { token ->
        require(OpaqueToken.matches(token)) { "Media reference tokens must be 128-bit lowercase hex" }
    }

    private fun reference(document: String, resource: String): String =
        "$WebMediaPathPrefix$document/$resource"

    private fun backoffMillis(attempts: Int): Long {
        val exponent = (attempts - 1).coerceIn(0, 30)
        val multiplier = 1L shl exponent
        return if (initialBackoffMillis > maxBackoffMillis / multiplier) {
            maxBackoffMillis
        } else {
            (initialBackoffMillis * multiplier).coerceAtMost(maxBackoffMillis)
        }
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val WebMediaHost = "appassets.androidplatform.net"
        const val WebMediaPathPrefix = "/media/v1/"
        const val MaxSlotLength = 256
        const val DefaultMaxEntries = 64
        const val DefaultMaxInFlight = 4
        const val CurrentPriorityLaneCount = 1
        const val DefaultMaxColdHistorySources = 8
        const val DefaultInitialBackoffMillis = 2_000L
        const val DefaultMaxBackoffMillis = 5L * 60L * 1_000L
        const val DefaultMaxLeasedBytes = StaticMediaDiskCacheBytes - StaticVideoMaxBytes
        val OpaqueToken = Regex("^[a-f0-9]{32}$")
        val ReferencePath = Regex("^/media/v1/([a-f0-9]{32})/([a-f0-9]{32})$")
    }
}

private fun sourceExtensionMatchesKind(sourceUrl: String, kind: WebMediaKind): Boolean {
    val path = runCatching { URI(sourceUrl).path }.getOrNull()
        ?.lowercase(Locale.ROOT)
        ?: return false
    return when (kind) {
        WebMediaKind.Video -> path.endsWith(".mp4")
        WebMediaKind.Image -> path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".webp")
    }
}

private fun webMediaSourceKey(sourceUrl: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(sourceUrl.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private object UnavailableMediaMaterializer : WebMediaMaterializer {
    override fun materialize(sourceUrl: String, kind: WebMediaKind): VerifiedWebMediaAsset =
        throw IOException("No verified media store is installed")
}

private val UnscopedTestMediaOwner = WebMediaOwnerScope("unscoped", "unscoped")

internal fun isWebMediaRequest(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme == "https" &&
        uri.host.equals("appassets.androidplatform.net", ignoreCase = true) &&
        uri.port == -1 &&
        uri.rawPath.orEmpty().startsWith("/media/")
}
