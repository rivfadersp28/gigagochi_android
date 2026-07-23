package com.gigagochi.app.core.webview

import com.gigagochi.app.core.network.StaticImageMaxBytes
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.core.network.StaticVideoMaxBytes
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebMediaReferenceRegistryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exposesStableOpaqueCapabilitiesAndOnlyVerifiedScopedLocalMetadata() {
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("verified"))
        val videoSource = "$BackendOrigin/static/generated/pet-idle.mp4?v=asset_1"
        val imageSource = "$BackendOrigin/static/pet.webp"
        materializer.cachedSources += setOf(videoSource, imageSource)
        val registry = registry(materializer, scopeProvider = {
            WebMediaOwnerScope("owner-1", "pet-1")
        })
        try {
            val reference = requireNotNull(registry.register(videoSource, WebMediaKind.Video))

            assertTrue(Reference.matches(reference))
            assertEquals(reference, registry.register(videoSource, WebMediaKind.Video))
            assertFalse(reference.contains("static"))
            assertFalse(reference.contains("gigagochi"))
            val registered = registry.resolveRequest("$GigagochiWebOrigin$reference")?.media
            assertEquals(listOf(videoSource), materializer.cacheChecks)
            assertEquals("owner-1", registered?.ownerId)
            assertEquals("pet-1", registered?.petId)
            assertEquals("video/mp4", registered?.mimeType)
            assertEquals(StaticVideoMaxBytes, registered?.maxResourceBytes)
            assertTrue(requireNotNull(registered).path.path.startsWith(temporaryFolder.root.path))
            assertFalse(registered.toString().contains(BackendOrigin))

            assertNull(registry.register("https://evil.example/static/pet.mp4", WebMediaKind.Video))
            assertNull(registry.register("$BackendOrigin/static/pet.png", WebMediaKind.Video))
            assertEquals(
                StaticImageMaxBytes,
                registry.register(imageSource, WebMediaKind.Image)
                    ?.let { registry.resolveRequest("$GigagochiWebOrigin$it") }
                    ?.media
                    ?.maxResourceBytes,
            )
        } finally {
            registry.close()
        }
    }

    @Test
    fun rejectsForgedRequestsAndExpiresEveryReferenceWithItsDocument() {
        val source = "$BackendOrigin/static/pet.jpg"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("revoke"))
        materializer.cachedSources += source
        val registry = registry(materializer)
        try {
            val reference = requireNotNull(registry.register(source, WebMediaKind.Image))
            val absolute = "$GigagochiWebOrigin$reference"
            val resourceToken = reference.substringAfterLast('/')

            listOf(
                reference,
                "$absolute?v=1",
                "$absolute#fragment",
                absolute.replace("https://", "http://"),
                absolute.replace("appassets.androidplatform.net", "evil.example"),
                absolute.replace(resourceToken, "d".repeat(32)),
                source,
                "$GigagochiWebOrigin/media/v1/%61${"a".repeat(30)}/${"b".repeat(32)}",
            ).forEach { forged ->
                assertNull(forged, registry.resolveRequest(forged))
            }

            val resolved = requireNotNull(registry.resolveRequest(absolute))
            assertTrue(registry.isActive(resolved))

            registry.invalidateDocument()

            assertNull(registry.resolveRequest(absolute))
            assertFalse(registry.isActive(resolved))
            assertEquals(1, materializer.releases.get())
            assertTrue(isWebMediaRequest(absolute))
            assertFalse(isWebMediaRequest(source))
        } finally {
            registry.close()
        }
    }

    @Test
    fun capabilityAllocationFailureReleasesTheNewlyAcquiredCacheLease() {
        val first = "$BackendOrigin/static/first.mp4"
        val second = "$BackendOrigin/static/second.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("token-failure"))
        materializer.cachedSources += setOf(first, second)
        val registry = WebMediaReferenceRegistry(
            urlPolicy = Policy,
            tokenFactory = { "a".repeat(32) },
            materializer = materializer,
        )
        try {
            assertTrue(registry.register(first, WebMediaKind.Video) != null)

            assertTrue(runCatching { registry.register(second, WebMediaKind.Video) }.isFailure)
            assertEquals(1, materializer.releases.get())
            assertEquals(1, registry.stats().entryCount)
            assertEquals(1L, registry.stats().uniqueLeasedBytes)
        } finally {
            registry.close()
        }
        assertEquals(2, materializer.releases.get())
    }

    @Test
    fun heldMaterializationIsDeduplicatedAndDoesNotBlockRegistryCalls() {
        val source = "$BackendOrigin/static/held.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("held"))
        val control = materializer.block(source)
        val publications = CopyOnWriteArrayList<WebMediaPublication>()
        val published = CountDownLatch(1)
        val registry = registry(materializer).also { target ->
            target.setPublicationListener { publication ->
                publications += publication
                published.countDown()
            }
        }
        try {
            val elapsed = measureTimeMillis {
                assertNull(registry.register(source, WebMediaKind.Video, slot = "dashboard"))
            }
            assertTrue("cold registration took ${elapsed}ms", elapsed < 500L)
            assertTrue(control.started.await(1, TimeUnit.SECONDS))

            repeat(10) {
                assertNull(registry.register(source, WebMediaKind.Video, slot = "dashboard"))
            }
            val statsElapsed = measureTimeMillis { registry.stats() }
            assertTrue("stats took ${statsElapsed}ms", statsElapsed < 500L)
            assertEquals(1, materializer.acquireCount(source))
            assertEquals(1, materializer.cacheChecks.count { it == source })

            control.release.countDown()
            assertTrue(published.await(2, TimeUnit.SECONDS))
            val reference = requireNotNull(
                registry.register(source, WebMediaKind.Video, slot = "dashboard"),
            )
            assertTrue(Reference.matches(reference))
            assertFalse(reference.contains(source))
            assertEquals(1, publications.size)
            assertTrue(registry.isPublicationCurrent(publications.single()))
        } finally {
            control.release.countDown()
            registry.close()
        }
    }

    @Test
    fun completionAfterDocumentInvalidationIsReleasedWithoutPublication() {
        val source = "$BackendOrigin/static/stale-document.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("stale-document"))
        val control = materializer.block(source)
        val publications = CopyOnWriteArrayList<WebMediaPublication>()
        val registry = registry(materializer).also { target ->
            target.setPublicationListener(publications::add)
        }
        try {
            assertNull(registry.register(source, WebMediaKind.Video, slot = "story"))
            assertTrue(control.started.await(1, TimeUnit.SECONDS))

            registry.invalidateDocument()
            control.release.countDown()

            assertTrue(control.finished.await(2, TimeUnit.SECONDS))
            waitUntil { registry.stats().inFlightCount == 0 }
            assertTrue(publications.isEmpty())
            assertEquals(1, materializer.releases.get())
            assertEquals(0, registry.stats().entryCount)
        } finally {
            control.release.countDown()
            registry.close()
        }
    }

    @Test
    fun staleSlotSourceNeverPublishesAndLatestVerifiedSourceWins() {
        val oldSource = "$BackendOrigin/static/old.mp4"
        val newSource = "$BackendOrigin/static/new.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("stale-source"))
        val oldControl = materializer.block(oldSource)
        val newControl = materializer.block(newSource)
        val publications = CopyOnWriteArrayList<WebMediaPublication>()
        val published = CountDownLatch(1)
        val registry = registry(materializer).also { target ->
            target.setPublicationListener { publication ->
                publications += publication
                published.countDown()
            }
        }
        try {
            assertNull(registry.register(oldSource, WebMediaKind.Video, slot = "dashboard"))
            assertTrue(oldControl.started.await(1, TimeUnit.SECONDS))
            assertNull(registry.register(newSource, WebMediaKind.Video, slot = "dashboard"))
            assertTrue(newControl.started.await(1, TimeUnit.SECONDS))

            oldControl.release.countDown()
            assertTrue(oldControl.finished.await(2, TimeUnit.SECONDS))
            waitUntil { materializer.releases.get() == 1 }
            assertTrue(publications.isEmpty())

            newControl.release.countDown()
            assertTrue(published.await(2, TimeUnit.SECONDS))
            val reference = requireNotNull(
                registry.register(newSource, WebMediaKind.Video, slot = "dashboard"),
            )
            assertEquals(1, publications.size)
            assertTrue(registry.isPublicationCurrent(publications.single()))
            assertTrue(
                requireNotNull(registry.resolveRequest("$GigagochiWebOrigin$reference"))
                    .let(registry::isActive),
            )
        } finally {
            oldControl.release.countDown()
            newControl.release.countDown()
            registry.close()
        }
    }

    @Test
    fun replacementFailureRetainsOldSlotBacksOffAndRetriesOnForeground() {
        val oldSource = "$BackendOrigin/static/old.mp4"
        val newSource = "$BackendOrigin/static/new.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("replacement"))
        materializer.cachedSources += oldSource
        materializer.failSources += newSource
        val publications = CopyOnWriteArrayList<WebMediaPublication>()
        val published = CountDownLatch(1)
        val registry = registry(materializer).also { target ->
            target.setPublicationListener { publication ->
                publications += publication
                published.countDown()
            }
        }
        try {
            val oldRef = requireNotNull(
                registry.register(oldSource, WebMediaKind.Video, slot = "dashboard"),
            )
            val oldResolved = requireNotNull(
                registry.resolveRequest("$GigagochiWebOrigin$oldRef"),
            )

            assertEquals(
                oldRef,
                registry.register(newSource, WebMediaKind.Video, slot = "dashboard"),
            )
            waitUntil { registry.stats().negativeSourceCount == 1 }
            assertTrue(registry.isActive(oldResolved))
            assertEquals(1, materializer.acquireCount(newSource))
            assertEquals(1, materializer.cacheChecks.count { it == newSource })

            repeat(10) {
                assertEquals(
                    oldRef,
                    registry.register(newSource, WebMediaKind.Video, slot = "dashboard"),
                )
            }
            assertEquals(1, materializer.acquireCount(newSource))
            assertEquals(1, materializer.cacheChecks.count { it == newSource })

            materializer.failSources -= newSource
            registry.retryFailedOnForeground()

            assertTrue(published.await(2, TimeUnit.SECONDS))
            val newRef = requireNotNull(
                registry.register(newSource, WebMediaKind.Video, slot = "dashboard"),
            )
            assertFalse(registry.isActive(oldResolved))
            assertNull(registry.resolveRequest("$GigagochiWebOrigin$oldRef"))
            assertTrue(
                requireNotNull(registry.resolveRequest("$GigagochiWebOrigin$newRef"))
                    .let(registry::isActive),
            )
            assertEquals(2, materializer.acquireCount(newSource))
            assertEquals(0, registry.stats().negativeSourceCount)
            assertTrue(registry.isPublicationCurrent(publications.single()))
        } finally {
            registry.close()
        }
    }

    @Test
    fun retryDeadlineAutomaticallyPublishesAfterOneTransientFailure() {
        val source = "$BackendOrigin/static/transient.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("automatic-backoff"))
        materializer.failNext(source)
        val publications = CopyOnWriteArrayList<WebMediaPublication>()
        val published = CountDownLatch(1)
        val registry = WebMediaReferenceRegistry(
            urlPolicy = Policy,
            tokenFactory = sequentialTokenFactory(),
            materializer = materializer,
            initialBackoffMillis = 20L,
            maxBackoffMillis = 20L,
        ).also { target ->
            target.setPublicationListener { publication ->
                publications += publication
                published.countDown()
            }
        }
        try {
            assertNull(registry.register(source, WebMediaKind.Video, slot = "current"))

            assertTrue(published.await(2, TimeUnit.SECONDS))
            assertEquals(2, materializer.acquireCount(source))
            assertEquals(1, materializer.cacheChecks.count { it == source })
            assertEquals(1, publications.size)
            assertTrue(registry.isPublicationCurrent(publications.single()))
            assertTrue(registry.register(source, WebMediaKind.Video, slot = "current") != null)
        } finally {
            registry.close()
        }
    }

    @Test
    fun currentPriorityClearsCapacityRejectionAndEvictsHistory() {
        val first = "$BackendOrigin/static/history-first.mp4"
        val second = "$BackendOrigin/static/history-second.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("priority"))
        materializer.cachedSources += setOf(first, second)
        val registry = registry(
            materializer = materializer,
            maxEntries = 1,
            maxColdHistorySources = 2,
        )
        try {
            lateinit var firstRef: String
            registry.projectSnapshot {
                firstRef = requireNotNull(
                    registry.register(
                        first,
                        WebMediaKind.Video,
                        slot = "first",
                        priority = WebMediaProjectionPriority.History,
                    ),
                )
                assertNull(
                    registry.register(
                        second,
                        WebMediaKind.Video,
                        slot = "second",
                        priority = WebMediaProjectionPriority.History,
                    ),
                )
            }
            assertNull(
                registry.register(
                    second,
                    WebMediaKind.Video,
                    slot = "second",
                    priority = WebMediaProjectionPriority.History,
                ),
            )
            assertEquals(1, materializer.cacheChecks.count { it == second })

            val currentRef = requireNotNull(
                registry.register(
                    second,
                    WebMediaKind.Video,
                    slot = "second",
                    priority = WebMediaProjectionPriority.Current,
                ),
            )

            assertNull(registry.resolveRequest("$GigagochiWebOrigin$firstRef"))
            assertTrue(Reference.matches(currentRef))
            assertEquals(2, materializer.cacheChecks.count { it == second })
            assertEquals(1, registry.stats().entryCount)
        } finally {
            registry.close()
        }
    }

    @Test
    fun currentRegisteredAfterHistoryWinsTheFirstMaterializationLane() {
        val history = "$BackendOrigin/static/history.mp4"
        val current = "$BackendOrigin/static/current.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("projection-priority"))
        val historyControl = materializer.block(history)
        val currentControl = materializer.block(current)
        val registry = registry(
            materializer = materializer,
            maxInFlight = 1,
            maxColdHistorySources = 1,
        )
        try {
            registry.projectSnapshot {
                assertNull(
                    registry.register(
                        history,
                        WebMediaKind.Video,
                        slot = "history",
                        priority = WebMediaProjectionPriority.History,
                    ),
                )
                assertNull(
                    registry.register(
                        current,
                        WebMediaKind.Video,
                        slot = "opened-story",
                        priority = WebMediaProjectionPriority.Current,
                    ),
                )
                assertEquals(0, materializer.totalAcquireCount())
            }

            assertTrue(currentControl.started.await(1, TimeUnit.SECONDS))
            assertEquals(1, materializer.acquireCount(current))
            assertEquals(0, materializer.acquireCount(history))

            currentControl.release.countDown()
            assertTrue(historyControl.started.await(2, TimeUnit.SECONDS))
            assertEquals(1, materializer.acquireCount(history))
        } finally {
            currentControl.release.countDown()
            historyControl.release.countDown()
            registry.close()
        }
    }

    @Test
    fun staleHeldHistoryCannotBlockCurrentInTheNextDocument() {
        val staleHistory = "$BackendOrigin/static/stale-held-history.mp4"
        val current = "$BackendOrigin/static/new-document-current.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("current-priority-lane"))
        val staleControl = materializer.block(staleHistory)
        val currentControl = materializer.block(current)
        val publications = CopyOnWriteArrayList<WebMediaPublication>()
        val registry = registry(
            materializer = materializer,
            maxInFlight = 1,
            maxColdHistorySources = 1,
        ).also { target -> target.setPublicationListener(publications::add) }
        try {
            registry.projectSnapshot {
                registry.register(
                    staleHistory,
                    WebMediaKind.Video,
                    slot = "history",
                    priority = WebMediaProjectionPriority.History,
                )
            }
            assertTrue(staleControl.started.await(1, TimeUnit.SECONDS))

            registry.invalidateDocument()
            registry.projectSnapshot {
                registry.register(
                    current,
                    WebMediaKind.Video,
                    slot = "current",
                    priority = WebMediaProjectionPriority.Current,
                )
            }

            assertTrue(currentControl.started.await(1, TimeUnit.SECONDS))
            assertEquals(1, materializer.acquireCount(staleHistory))
            assertEquals(1, materializer.acquireCount(current))
            assertEquals(2, registry.stats().inFlightCount)

            staleControl.release.countDown()
            assertTrue(staleControl.finished.await(2, TimeUnit.SECONDS))
            waitUntil { materializer.releases.get() == 1 }
            assertTrue(publications.isEmpty())

            currentControl.release.countDown()
            waitUntil { publications.size == 1 }
            assertTrue(registry.isPublicationCurrent(publications.single()))
        } finally {
            staleControl.release.countDown()
            currentControl.release.countDown()
            registry.close()
        }
    }

    @Test
    fun obsoleteHeldCurrentCannotBlockTheNextSnapshotCurrent() {
        val obsolete = "$BackendOrigin/static/obsolete-current.mp4"
        val newest = "$BackendOrigin/static/latest-current.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("obsolete-current-lane"))
        val obsoleteControl = materializer.block(obsolete)
        val newestControl = materializer.block(newest)
        val publications = CopyOnWriteArrayList<WebMediaPublication>()
        val registry = registry(materializer = materializer, maxInFlight = 1).also { target ->
            target.setPublicationListener(publications::add)
        }
        try {
            registry.projectSnapshot {
                registry.register(
                    obsolete,
                    WebMediaKind.Video,
                    slot = "current",
                    priority = WebMediaProjectionPriority.Current,
                )
            }
            assertTrue(obsoleteControl.started.await(1, TimeUnit.SECONDS))

            registry.projectSnapshot {
                registry.register(
                    newest,
                    WebMediaKind.Video,
                    slot = "current",
                    priority = WebMediaProjectionPriority.Current,
                )
            }

            assertTrue(newestControl.started.await(1, TimeUnit.SECONDS))
            assertEquals(2, registry.stats().inFlightCount)
            assertEquals(1, materializer.acquireCount(obsolete))
            assertEquals(1, materializer.acquireCount(newest))

            obsoleteControl.release.countDown()
            assertTrue(obsoleteControl.finished.await(2, TimeUnit.SECONDS))
            waitUntil { materializer.releases.get() == 1 }
            assertTrue(publications.isEmpty())

            newestControl.release.countDown()
            waitUntil { publications.size == 1 }
            assertTrue(registry.isPublicationCurrent(publications.single()))
        } finally {
            obsoleteControl.release.countDown()
            newestControl.release.countDown()
            registry.close()
        }
    }

    @Test
    fun closeDuringHeldMaterializationNeverPublishesAndEventuallyBalancesLease() {
        val source = "$BackendOrigin/static/close-held.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("close-held"))
        val control = materializer.block(source)
        val publications = CopyOnWriteArrayList<WebMediaPublication>()
        val registry = registry(materializer).also { target ->
            target.setPublicationListener(publications::add)
        }

        assertNull(registry.register(source, WebMediaKind.Video, slot = "current"))
        assertTrue(control.started.await(1, TimeUnit.SECONDS))
        registry.close()
        control.release.countDown()

        assertTrue(control.finished.await(2, TimeUnit.SECONDS))
        waitUntil { registry.stats().inFlightCount == 0 }
        assertEquals(1, materializer.releases.get())
        assertTrue(publications.isEmpty())
        assertEquals(0, registry.stats().entryCount)
    }

    @Test
    fun removingAnAbsentCurrentEntryUnblocksRejectedHistoryNextCycle() {
        val current = "$BackendOrigin/static/current-cached.mp4"
        val history = "$BackendOrigin/static/history-waiting.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("capacity-recovery"))
        materializer.cachedSources += current
        val historyControl = materializer.block(history)
        val registry = registry(
            materializer = materializer,
            maxEntries = 1,
            maxInFlight = 1,
            maxColdHistorySources = 1,
        )
        try {
            registry.projectSnapshot {
                assertTrue(
                    registry.register(
                        current,
                        WebMediaKind.Video,
                        slot = "current",
                        priority = WebMediaProjectionPriority.Current,
                    ) != null,
                )
                assertNull(
                    registry.register(
                        history,
                        WebMediaKind.Video,
                        slot = "history",
                        priority = WebMediaProjectionPriority.History,
                    ),
                )
            }
            assertEquals(0, materializer.acquireCount(history))

            registry.projectSnapshot {
                assertNull(
                    registry.register(
                        history,
                        WebMediaKind.Video,
                        slot = "history",
                        priority = WebMediaProjectionPriority.History,
                    ),
                )
            }

            assertTrue(historyControl.started.await(1, TimeUnit.SECONDS))
            assertEquals(1, materializer.acquireCount(history))
        } finally {
            historyControl.release.countDown()
            registry.close()
        }
    }

    @Test
    fun expiredOldHistoryRetryCannotPreemptNewHistoryDuringNextProjection() {
        val old = "$BackendOrigin/static/old-failed-history.mp4"
        val newest = "$BackendOrigin/static/new-history.mp4"
        var now = 0L
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("history-retry-order"))
        materializer.failSources += old
        val registry = WebMediaReferenceRegistry(
            urlPolicy = Policy,
            tokenFactory = sequentialTokenFactory(),
            materializer = materializer,
            maxInFlight = 1,
            maxColdHistorySources = 1,
            initialBackoffMillis = 10L,
            maxBackoffMillis = 10L,
            nowMillis = { now },
            retryDelayMillis = { kotlinx.coroutines.awaitCancellation() },
        )
        var oldRetryControl: Control? = null
        var newestControl: Control? = null
        try {
            registry.projectSnapshot {
                registry.register(
                    old,
                    WebMediaKind.Video,
                    slot = "old",
                    priority = WebMediaProjectionPriority.History,
                )
            }
            waitUntil {
                materializer.acquireCount(old) == 1 && registry.stats().negativeSourceCount == 1
            }

            materializer.failSources -= old
            oldRetryControl = materializer.block(old)
            newestControl = materializer.block(newest)
            now = 20L
            registry.projectSnapshot {
                registry.register(
                    newest,
                    WebMediaKind.Video,
                    slot = "newest",
                    priority = WebMediaProjectionPriority.History,
                )
                assertEquals(1, materializer.acquireCount(old))
                assertEquals(0, materializer.acquireCount(newest))
            }

            assertTrue(newestControl.started.await(1, TimeUnit.SECONDS))
            assertEquals(1, materializer.acquireCount(old))
            assertEquals(1, materializer.acquireCount(newest))
            assertEquals(1L, oldRetryControl.started.count)
        } finally {
            oldRetryControl?.release?.countDown()
            newestControl?.release?.countDown()
            registry.close()
        }
    }

    @Test
    fun repeatedOfflineHistoryRotationsKeepNegativeBookkeepingBounded() {
        var now = 0L
        val sources = (0 until 20).map { index ->
            "$BackendOrigin/static/offline-rotation-$index.mp4"
        }
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("negative-budget"))
        materializer.failSources += sources
        val registry = WebMediaReferenceRegistry(
            urlPolicy = Policy,
            tokenFactory = sequentialTokenFactory(),
            materializer = materializer,
            maxInFlight = 1,
            maxColdHistorySources = 1,
            initialBackoffMillis = 10L,
            maxBackoffMillis = 10L,
            nowMillis = { now },
            retryDelayMillis = { kotlinx.coroutines.awaitCancellation() },
        )
        try {
            sources.forEachIndexed { index, source ->
                registry.projectSnapshot {
                    sources.take(index + 1).asReversed().forEach { candidate ->
                        registry.register(
                            candidate,
                            WebMediaKind.Video,
                            slot = candidate.substringAfterLast('/').substringBeforeLast('.'),
                            priority = WebMediaProjectionPriority.History,
                        )
                    }
                }
                waitUntil {
                    materializer.acquireCount(source) == 1 &&
                        registry.stats().inFlightCount == 0
                }
                assertTrue(registry.stats().negativeSourceCount <= 1)
                now += 20L
            }

            assertEquals(20, materializer.cacheChecks.size)
            assertEquals(20, materializer.totalAcquireCount())
            assertEquals(1, registry.stats().negativeSourceCount)
            assertEquals(1, registry.stats().admittedColdHistoryCount)
        } finally {
            registry.close()
        }
    }

    @Test
    fun historyProjectionBoundsChecksDownloadsLeasesAndRotatesNewestSources() {
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("history-budget"))
        val registry = registry(
            materializer = materializer,
            maxEntries = 8,
            maxLeasedBytes = 8L,
            maxInFlight = 2,
            maxColdHistorySources = 8,
        )
        val sources = (0 until 1_000).map { index ->
            "$BackendOrigin/static/history-$index.mp4"
        }
        try {
            projectHistory(registry, sources)

            assertEquals(8, materializer.cacheChecks.size)
            assertTrue(registry.stats().inFlightCount <= 2)
            waitUntil(timeoutMillis = 4_000L) {
                materializer.totalAcquireCount() == 8 && registry.stats().inFlightCount == 0
            }
            assertEquals(8, registry.stats().entryCount)
            assertEquals(8L, registry.stats().uniqueLeasedBytes)
            assertEquals(8, registry.stats().admittedColdHistoryCount)

            projectHistory(registry, sources)
            assertEquals(8, materializer.cacheChecks.size)
            assertEquals(8, materializer.totalAcquireCount())

            val newest = "$BackendOrigin/static/history-newest.mp4"
            val rotated = listOf(newest) + sources.take(999)
            projectHistory(registry, rotated)
            waitUntil(timeoutMillis = 4_000L) {
                materializer.acquireCount(newest) == 1 && registry.stats().inFlightCount == 0
            }

            assertEquals(9, materializer.cacheChecks.size)
            assertEquals(9, materializer.totalAcquireCount())
            assertEquals(8, registry.stats().entryCount)
            assertEquals(8L, registry.stats().uniqueLeasedBytes)
            assertEquals(8, registry.stats().admittedColdHistoryCount)
            assertTrue(
                registry.register(
                    newest,
                    WebMediaKind.Video,
                    slot = "history-newest",
                    priority = WebMediaProjectionPriority.History,
                ) != null,
            )
        } finally {
            registry.close()
        }
    }

    @Test
    fun aFormerCurrentEntryIsDemotedAndReleasedWhenOnlyUnadmittedHistoryRemains() {
        val source = "$BackendOrigin/static/former-current.mp4"
        val materializer = ControlledMaterializer(temporaryFolder.newFolder("demotion"))
        materializer.cachedSources += source
        val registry = registry(materializer, maxColdHistorySources = 0)
        try {
            registry.projectSnapshot {
                assertTrue(
                    registry.register(
                        source,
                        WebMediaKind.Video,
                        priority = WebMediaProjectionPriority.Current,
                    ) != null,
                )
            }
            assertEquals(1, registry.stats().entryCount)

            registry.projectSnapshot {
                assertNull(
                    registry.register(
                        source,
                        WebMediaKind.Video,
                        priority = WebMediaProjectionPriority.History,
                    ),
                )
            }

            assertEquals(0, registry.stats().entryCount)
            assertEquals(0L, registry.stats().uniqueLeasedBytes)
            assertEquals(1, materializer.releases.get())
        } finally {
            registry.close()
        }
    }

    private fun projectHistory(
        registry: WebMediaReferenceRegistry,
        sources: List<String>,
    ) {
        registry.projectSnapshot {
            sources.forEach { source ->
                registry.register(
                    source,
                    WebMediaKind.Video,
                    slot = source.substringAfterLast('/').substringBeforeLast('.'),
                    priority = WebMediaProjectionPriority.History,
                )
            }
        }
    }

    private fun registry(
        materializer: WebMediaMaterializer,
        scopeProvider: () -> WebMediaOwnerScope? = {
            WebMediaOwnerScope("owner", "pet")
        },
        maxEntries: Int = 64,
        maxLeasedBytes: Long = 1024L,
        maxInFlight: Int = 4,
        maxColdHistorySources: Int = 8,
    ): WebMediaReferenceRegistry = WebMediaReferenceRegistry(
        urlPolicy = Policy,
        tokenFactory = sequentialTokenFactory(),
        materializer = materializer,
        scopeProvider = scopeProvider,
        maxEntries = maxEntries,
        maxLeasedBytes = maxLeasedBytes,
        maxInFlight = maxInFlight,
        maxColdHistorySources = maxColdHistorySources,
    )

    private fun sequentialTokenFactory(): () -> String {
        val next = AtomicInteger()
        return {
            next.incrementAndGet().toString(16).padStart(32, '0')
        }
    }

    private fun waitUntil(
        timeoutMillis: Long = 2_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Condition was not met within ${timeoutMillis}ms")
            Thread.sleep(5L)
        }
    }

    private class ControlledMaterializer(
        private val directory: File,
    ) : WebMediaMaterializer {
        val cachedSources: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val failSources: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val cacheChecks = CopyOnWriteArrayList<String>()
        val releases = AtomicInteger()
        private val sequence = AtomicInteger()
        private val assets = ConcurrentHashMap<String, VerifiedWebMediaAsset>()
        private val controls = ConcurrentHashMap<String, Control>()
        private val acquireCounts = ConcurrentHashMap<String, AtomicInteger>()
        private val failuresRemaining = ConcurrentHashMap<String, AtomicInteger>()

        fun block(sourceUrl: String): Control = Control().also { controls[sourceUrl] = it }

        fun acquireCount(sourceUrl: String): Int = acquireCounts[sourceUrl]?.get() ?: 0

        fun failNext(sourceUrl: String, count: Int = 1) {
            failuresRemaining[sourceUrl] = AtomicInteger(count)
        }

        fun totalAcquireCount(): Int = acquireCounts.values.sumOf(AtomicInteger::get)

        override fun acquireCached(
            sourceUrl: String,
            kind: WebMediaKind,
        ): VerifiedWebMediaAsset? {
            cacheChecks += sourceUrl
            return if (sourceUrl in cachedSources) asset(sourceUrl, kind) else null
        }

        override fun materialize(
            sourceUrl: String,
            kind: WebMediaKind,
        ): VerifiedWebMediaAsset = acquire(sourceUrl, kind)

        override fun acquire(
            sourceUrl: String,
            kind: WebMediaKind,
        ): VerifiedWebMediaAsset {
            acquireCounts.computeIfAbsent(sourceUrl) { AtomicInteger() }.incrementAndGet()
            val control = controls[sourceUrl]
            control?.started?.countDown()
            try {
                if (control != null && !control.release.await(5, TimeUnit.SECONDS)) {
                    throw IOException("test materialization was not released")
                }
                val transientFailure = failuresRemaining[sourceUrl]?.let { remaining ->
                    while (true) {
                        val current = remaining.get()
                        if (current <= 0) return@let false
                        if (remaining.compareAndSet(current, current - 1)) return@let true
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                } == true
                if (sourceUrl in failSources || transientFailure) throw IOException("download failed")
                return asset(sourceUrl, kind)
            } finally {
                control?.finished?.countDown()
            }
        }

        override fun release(asset: VerifiedWebMediaAsset) {
            releases.incrementAndGet()
        }

        private fun asset(sourceUrl: String, kind: WebMediaKind): VerifiedWebMediaAsset =
            assets.computeIfAbsent("${kind.name}:$sourceUrl") {
                val index = sequence.incrementAndGet()
                val file = File(directory, "$index.media").apply { writeBytes(byteArrayOf(index.toByte())) }
                val mime = if (kind == WebMediaKind.Video) {
                    "video/mp4"
                } else {
                    when {
                        sourceUrl.substringBefore('?').endsWith(".jpg") -> "image/jpeg"
                        sourceUrl.substringBefore('?').endsWith(".webp") -> "image/webp"
                        else -> "image/png"
                    }
                }
                VerifiedWebMediaAsset(file, mime, file.length(), "version-$index")
            }
    }

    private class Control {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
    }

    private companion object {
        const val BackendOrigin = "https://gigagochi.serega.works"
        const val GigagochiWebOrigin = "https://appassets.androidplatform.net"
        val Policy = StaticMediaUrlPolicy("$BackendOrigin/", false)
        val Reference = Regex("^/media/v1/[a-f0-9]{32}/[a-f0-9]{32}$")
    }
}
