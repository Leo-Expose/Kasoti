package dev.kasoti.sync

import dev.kasoti.diary.CrossingEvent
import dev.kasoti.diary.FakeDigest
import dev.kasoti.diary.FakeHmac
import dev.kasoti.diary.FileDiary
import dev.kasoti.diary.Fixtures
import dev.kasoti.diary.InMemoryDiaryStorage
import dev.kasoti.diary.InMemoryQuarantine
import dev.kasoti.diary.IsoInstant
import dev.kasoti.diary.QuarantineReason
import dev.kasoti.diary.Ulid
import dev.kasoti.fusion.FindingCode
import dev.kasoti.json.Base64Codec
import dev.kasoti.json.CanonicalJson
import dev.kasoti.json.JsonParser
import dev.kasoti.json.JsonValue
import dev.kasoti.diary.DiaryModelMismatchException
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `kasoti-sync/1` tests: golden vectors, mutation, replay, determinism, quarantine, skew and
 * the transport budgets.
 *
 * The mutation tests carry most of the weight here. A sync implementation that only ever sees
 * bundles it produced itself will pass every happy-path test in the file and still be trivially
 * forgeable, so every single-byte mutation of a real bundle must end in a rejection.
 */
class SyncTest {

    private val mac = FakeHmac()
    private val exporter = BundleExporter(mac)
    private val reg = Fixtures.REGISTRY
    private val key = SyncVectors.KEY

    private fun diary(model: String = Fixtures.EMB_MODEL) =
        FileDiary(InMemoryDiaryStorage(), FakeDigest(), model)

    private fun importer(
        localDevice: String = "post3-ph1",
        localModel: String = Fixtures.EMB_MODEL,
    ) = BundleImporter(mac, localDevice, localModel, reg)

    private fun bundleOf(count: Int, device: String = "post7-ph1", model: String = Fixtures.EMB_MODEL) = exporter.export(
        device = device,
        records = (1..count).map { Fixtures.event(it, seq = it.toLong(), device = device, embModel = model) },
        key = key,
        nowMillis = Fixtures.T0_MILLIS,
        embModel = model,
        post = "RAXAUL",
    )

    // --- golden vectors ---------------------------------------------------------------------

    @Test
    fun canonicalFormMatchesTheGoldenVectors() {
        for (vector in SyncVectors.CANONICAL) {
            val parsed = assertNotNull(JsonParser.parseOrNull(vector.input), "vector ${vector.name} must parse")
            assertEquals(vector.canonical, CanonicalJson.write(parsed), "vector ${vector.name}")
            assertEquals(
                vector.canonical,
                CanonicalJson.write(JsonParser.parse(vector.canonical)),
                "vector ${vector.name} must be a fixed point",
            )
        }
    }

    @Test
    fun committedHmacVectorsAreWellFormed() {
        for (vector in SyncVectors.HMAC) {
            assertTrue(SyncVectors.isWellFormedHex(vector.expectedHex), "vector ${vector.name}")
            assertEquals(32, assertNotNull(SyncVectors.decodeOrNull(vector.expectedHex)).size)
        }
    }

    @Test
    fun theAuthenticatedPayloadIsTheHeaderWithoutTheHmac() {
        val bundle = bundleOf(2)
        assertTrue(bundle.text.contains("\"hmac\":\""), "sanity: the encoded envelope does carry an hmac")
        val envelope = assertNotNull(SyncEnvelope.decode(bundle.bytes))
        val headerOnly = CanonicalJson.write(envelope.headerJson())
        assertEquals(
            String(envelope.payloadBytes(), Charsets.UTF_8),
            headerOnly,
            "payloadBytes must be exactly the canonical header minus hmac",
        )
        assertFalse(headerOnly.contains("\"hmac\""), "the hmac field must not authenticate itself")
        assertEquals(bundle.text, CanonicalJson.write(envelope.toJson()), "encode must be a fixed point")
    }

    @Test
    fun recordsTravelAsObjectsNotAsEscapedStrings() {
        val text = bundleOf(1).text
        assertTrue(text.contains("\"records\":[{"), "records must be nested objects on the wire: $text")
        assertFalse(text.contains("\\\"device\\\""), "records must not be double-encoded strings")
    }

    @Test
    fun everySingleByteFlipBreaksTheBundle() {
        val original = bundleOf(2).text
        val bytes = original.toByteArray(Charsets.UTF_8)
        var survivors = 0
        for (index in bytes.indices) {
            for (bit in intArrayOf(0x01, 0x10, 0x80)) {
                val mutated = bytes.copyOf()
                mutated[index] = (mutated[index].toInt() xor bit).toByte()
                val text = String(mutated, Charsets.UTF_8)
                val envelope = SyncEnvelope.decode(text)
                val stillValid = envelope != null && envelope.verify(mac, key)
                if (stillValid) survivors++
            }
        }
        assertEquals(0, survivors, "a flipped bit anywhere in the bundle must break authentication")
    }

    @Test
    fun swappingARecordBreaksTheBundle() {
        val bundle = bundleOf(2)
        val envelope = assertNotNull(SyncEnvelope.decode(bundle.bytes))
        val swapped = envelope.copy(records = listOf(Fixtures.event(99, seq = 1, device = "post7-ph1").canonical()))
        val diary = diary()
        val report = importer().import(swapped.encode(), key, diary, Fixtures.T0_MILLIS)
        assertEquals(MergeOutcome.REJECTED_HMAC, report.outcome)
        assertEquals(0, diary.size(), "nothing may be applied when the MAC does not verify")
    }

    // --- import ordering and atomicity --------------------------------------------------------

    @Test
    fun aByteFlipRejectsTheWholeFileAndAppliesNothing() {
        val diary = diary()
        diary.append(Fixtures.event(1, seq = 0, post = "PRIOR"))
        val before = diary.tip()
        val bytes = bundleOf(5).bytes
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()

        val report = importer().import(bytes, key, diary, Fixtures.T0_MILLIS)
        assertEquals(MergeOutcome.REJECTED_HMAC, report.outcome)
        assertEquals(0, report.accepted)
        assertEquals(before, diary.tip(), "a rejected import must not touch the diary at all")
        assertEquals(1, diary.size())
    }

    @Test
    fun aBundleFromOurOwnDeviceIsRejected() {
        val diary = diary()
        val ownId = exporter.export(
            "post3-ph1",
            (1..3).map { Fixtures.event(it, seq = it.toLong(), device = "post3-ph1") },
            key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL",
        )
        val report = importer().import(ownId.bytes, key, diary, Fixtures.T0_MILLIS)
        assertEquals(MergeOutcome.REJECTED_SELF_DEVICE, report.outcome)
        assertEquals(0, diary.size())
    }

    @Test
    fun anEmptyBundleIsAnExplicitNoOp() {
        val diary = diary()
        val empty = exporter.export("post7-ph1", emptyList(), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        val report = importer().import(empty.bytes, key, diary, Fixtures.T0_MILLIS)
        assertEquals(MergeOutcome.EMPTY, report.outcome)
        assertEquals(0, diary.size())
    }

    @Test
    fun malformedAndFutureVersionsAreRejectedNotGuessed() {
        val diary = diary()
        for (text in listOf("", "not json", "[]", "{\"v\":2}", SyncVectors.CANONICAL_EMPTY_ENVELOPE_PAYLOAD)) {
            val report = importer().import(text, key, diary, Fixtures.T0_MILLIS)
            assertEquals(MergeOutcome.REJECTED_MALFORMED, report.outcome, "for input: ${text.take(40)}")
        }
        assertEquals(0, diary.size())
    }

    // --- idempotence, replay, determinism --------------------------------------------------------

    @Test
    fun reimportingTheSameBundleIsAllDupes() {
        val diary = diary()
        val bundle = bundleOf(6)
        val first = importer().import(bundle.bytes, key, diary, Fixtures.T0_MILLIS)
        assertEquals(6, first.accepted)
        assertEquals(MergeOutcome.APPLIED, first.outcome)

        val second = importer().import(bundle.bytes, key, diary, Fixtures.T0_MILLIS)
        assertEquals(0, second.accepted)
        assertEquals(6, second.dupes, "a re-import is a dupe, never a duplicate write")
        assertEquals(0, second.rejected)
        assertEquals(6, diary.size())
        assertEquals(first.tipAfter, second.tipAfter, "an idempotent import must not move the tip")
    }

    @Test
    fun anOldBundleAfterANewerOneIsRejectedAsReplay() {
        val diary = diary()
        val older = exporter.export(
            "post7-ph1",
            (1..3).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") },
            key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL",
        )
        val newer = exporter.export(
            "post7-ph1",
            (4..9).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") },
            key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL",
        )
        assertEquals(6, importer().import(newer.bytes, key, diary, Fixtures.T0_MILLIS).accepted)
        assertEquals(9L, diary.maxSeq("post7-ph1"), "the high-water mark is the newest sequence, not the record count")

        val replay = importer().import(older.bytes, key, diary, Fixtures.T0_MILLIS)
        assertEquals(0, replay.accepted)
        assertEquals(3, replay.rejected, "seq <= maxSeq[device] is a replay (invariant I2)")
        assertEquals(0, replay.dupes, "these ids are new to us, so they are replays rather than duplicates")
        assertEquals(6, diary.size())
    }

    @Test
    fun aPerDeviceHighWaterMarkDoesNotBlockAnotherDevice() {
        val diary = diary()
        importer().import(bundleOf(5, device = "post7-ph1").bytes, key, diary, Fixtures.T0_MILLIS)
        val other = exporter.export(
            "post9-ph1",
            (1..3).map { Fixtures.event(it, seq = it.toLong(), device = "post9-ph1") },
            key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL",
        )
        assertEquals(3, importer().import(other.bytes, key, diary, Fixtures.T0_MILLIS).accepted)
        assertEquals(8, diary.size())
    }

    @Test
    fun outOfOrderArrivalYieldsTheSameDiaryEveryTime() {
        val records = (1..20).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") }
        val tips = listOf(
            records.shuffled(Random(1)),
            records.shuffled(Random(2)),
            records.shuffled(Random(3)),
            records.reversed(),
        ).map { order ->
            val log = diary()
            val bundle = exporter.export("post7-ph1", order, key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
            val report = importer().import(bundle.bytes, key, log, Fixtures.T0_MILLIS)
            assertEquals(20, report.accepted)
            log.tip()
        }
        assertEquals(1, tips.distinct().size, "same set, different arrival order, same tip: $tips")
    }

    @Test
    fun exportingTheSameSliceTwiceIsByteIdentical() {
        val records = (1..8).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") }
        val a = exporter.export("post7-ph1", records, key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        val b = exporter.export("post7-ph1", records.reversed(), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        assertEquals(a.text, b.text)
        assertEquals("kasoti_sync_RAXAUL_1_8_post7-ph1.json", a.fileName)
    }

    // --- model mismatch (invariant I1) --------------------------------------------------------------

    @Test
    fun aForeignGenerationIsQuarantinedWholeAndNeverPartially() {
        val diary = diary()
        val quarantine = InMemoryQuarantine()
        val foreign = bundleOf(4, model = Fixtures.OTHER_EMB_MODEL)
        val report = importer().import(foreign.bytes, key, diary, Fixtures.T0_MILLIS, quarantine)

        assertEquals(MergeOutcome.QUARANTINED_MODEL_MISMATCH, report.outcome)
        assertEquals(0, report.accepted)
        assertEquals(4, report.quarantined)
        assertEquals(0, diary.size(), "not one record may land")
        assertEquals(QuarantineReason.EMB_MODEL_MISMATCH, quarantine.batches.single().reason)
        assertTrue(quarantine.batches.isNotEmpty(), "a quarantine that leaves no trace is an invisible data loss")
    }

    @Test
    fun recordsThatContradictTheirOwnHeaderAreQuarantinedWhole() {
        val diary = diary()
        val quarantine = InMemoryQuarantine()
        val mixed = listOf(
            Fixtures.event(1, seq = 1, device = "post7-ph1"),
            Fixtures.event(2, seq = 2, device = "post7-ph1", embModel = Fixtures.OTHER_EMB_MODEL),
            Fixtures.event(3, seq = 3, device = "post7-ph1"),
        )
        // A correct header, a correct HMAC, and a record that lies about its own generation.
        val forged = exporter.export("post7-ph1", mixed, key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        val report = importer().import(forged.bytes, key, diary, Fixtures.T0_MILLIS, quarantine)

        assertEquals(MergeOutcome.QUARANTINED_MODEL_MISMATCH, report.outcome)
        assertEquals(0, diary.size(), "partial application here would leave a diary that looks complete")
        assertEquals(QuarantineReason.EMB_MODEL_MISMATCH, quarantine.batches.single().reason)
    }

    @Test
    fun aLocalDiaryRefusesToStoreAForeignGenerationEvenWithoutSync() {
        val log = diary()
        assertFailsWith<DiaryModelMismatchException> {
            log.append(Fixtures.event(1, seq = 0, embModel = Fixtures.OTHER_EMB_MODEL))
        }
    }

    // --- skew -------------------------------------------------------------------------------------------

    @Test
    fun clockSkewBeyondTheRegistryLimitIsAWarningNotARejection() {
        val diary = diary()
        val skewMinutes = reg[ThresholdName.CLOCK_SKEW_MAX_MIN] * 4
        val late = Fixtures.T0_MILLIS + (skewMinutes * 60_000).toLong()
        val report = importer().import(bundleOf(3).bytes, key, diary, late)

        assertEquals(MergeOutcome.APPLIED, report.outcome, "skew is reported, never used to reject evidence")
        assertEquals(3, report.accepted)
        val warning = assertNotNull(report.skewWarnings.singleOrNull())
        assertEquals("post7-ph1", warning.device)
        assertTrue(warning.parseable)
        assertTrue(abs(warning.skewMinutes - skewMinutes) < 0.01)
    }

    @Test
    fun smallSkewDoesNotWarn() {
        val diary = diary()
        val within = Fixtures.T0_MILLIS + (reg[ThresholdName.CLOCK_SKEW_MAX_MIN] * 60_000).toLong() - 1000
        assertTrue(importer().import(bundleOf(2).bytes, key, diary, within).skewWarnings.isEmpty())
    }

    @Test
    fun anUnreadableEnvelopeTimestampIsItselfAWarning() {
        val diary = diary()
        val good = bundleOf(1)
        val envelope = assertNotNull(SyncEnvelope.decode(good.bytes))
        val broken = envelope.copy(ts = "whenever").signed(mac, key)
        val report = importer().import(broken.encode(), key, diary, Fixtures.T0_MILLIS)
        assertEquals(MergeOutcome.APPLIED, report.outcome)
        val warning = assertNotNull(report.skewWarnings.singleOrNull())
        assertFalse(warning.parseable)
        assertEquals("whenever", warning.envelopeTs)
    }

    // --- adversarial record content -----------------------------------------------------------------------

    @Test
    fun anUnreadableRecordIsCountedRatherThanFailingTheBundle() {
        val diary = diary()
        val good = (1..3).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") }
        val base = exporter.export("post7-ph1", good, key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        val parsed = assertNotNull(SyncEnvelope.decode(base.bytes))
        val withGarbage = parsed.copy(
            records = parsed.records + """{"id":"evt_01ARZ3NDEKTSV4RRFFQ69G5FAV","q":"not a number"}""",
        ).signed(mac, key)

        val report = importer().import(withGarbage.encode(), key, diary, Fixtures.T0_MILLIS)
        assertEquals(3, report.accepted, "one bad record must not cost the good ones")
        assertEquals(1, report.rejected)
    }

    @Test
    fun duplicateIdsInsideOneBundleLandOnce() {
        val diary = diary()
        val one = Fixtures.event(1, seq = 1, device = "post7-ph1")
        val two = Fixtures.event(2, seq = 2, device = "post7-ph1")
        val bundle = exporter.export("post7-ph1", listOf(one, two, one, two), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        val report = importer().import(bundle.bytes, key, diary, Fixtures.T0_MILLIS)
        assertEquals(2, report.accepted)
        assertEquals(2, report.dupes)
        assertEquals(2, diary.size())
    }

    @Test
    fun recordsWithoutASequenceAreRejectedBecauseTheyCannotBeReplayGuarded() {
        val diary = diary()
        val unsequenced = Fixtures.event(1, seq = 0, device = "post7-ph1")
        val bundle = exporter.export("post7-ph1", listOf(unsequenced), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        assertEquals(1, importer().import(bundle.bytes, key, diary, Fixtures.T0_MILLIS).rejected)
        assertEquals(0, diary.size())
    }

    @Test
    fun unknownFieldsInsideARecordSurviveAndStillAuthenticate() {
        val diary = diary()
        val event = Fixtures.event(1, seq = 1, device = "post7-ph1")
        val canonical = event.canonical()
        // Sorted position matters: a newer device emits canonical bytes, and a bundle whose
        // records are not canonical is rejected by the MAC check — which is the correct
        // fail-closed answer, just not what this test is about.
        val extended = canonical.dropLast(1) + ",\"xFuture\":{\"a\":[1,null]}}"
        val bundle = exporter.export("post7-ph1", listOf(event), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        val parsed = assertNotNull(SyncEnvelope.decode(bundle.bytes))
        val forward = parsed.copy(records = listOf(extended)).signed(mac, key)

        val report = importer().import(forward.encode(), key, diary, Fixtures.T0_MILLIS)
        assertEquals(1, report.accepted)
        assertEquals(1, assertNotNull(diary.get(event.id)).extra.size)
    }

    @Test
    fun unicodeAndVeryLongFieldValuesSurviveABundleRoundTrip() {
        val diary = diary()
        val longPost = "सीमा-पोस्ट-" + "क".repeat(600)
        val event = Fixtures.event(1, seq = 1, device = "post7-ph1", post = longPost)
        val bundle = exporter.export("post7-ph1", listOf(event), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        assertEquals(1, importer().import(bundle.bytes, key, diary, Fixtures.T0_MILLIS).accepted)
        assertEquals(longPost, assertNotNull(diary.get(event.id)).post)
    }

    @Test
    fun aMergeRecomputesTravelFlagsOverTheNewNeighbourhood() {
        val diary = diary()
        val posts = Fixtures.postMap()
        val first = Fixtures.event(1, seq = 1, device = "post7-ph1", post = "RAXAUL", ts = "2026-03-14T09:00:00.000Z")
        val second = Fixtures.event(2, seq = 2, device = "post7-ph1", post = "NEEMUCH", ts = "2026-03-14T09:30:00.000Z")
        val bundle = exporter.export("post7-ph1", listOf(first, second), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")

        val report = importer().import(bundle.bytes, key, diary, Fixtures.T0_MILLIS, InMemoryQuarantine(), posts)
        assertEquals(2, report.accepted)
        assertTrue(
            report.newFlags.any { it.code == FindingCode.R_TRAV_01 },
            "an impossible hop inside the merged window must surface in the merge report",
        )
    }

    // --- transport budgets ---------------------------------------------------------------------------------

    @Test
    fun theDefaultFileSplitIsTheFiveMegabytesSyncMandates() {
        assertEquals(5_242_880.0, ThresholdName.SYNC_FILE_SPLIT_BYTES.default, "SYNC.md §5")
    }

    @Test
    fun bundlesSplitUnderTheTransportLimitAndImportIdentically() {
        // The budget is lowered to the policy floor rather than the 5 MB default: 400 records are
        // ~180 KB, so the split would need 12k fixtures to reach 5 MB and the property under test
        // is the splitting, not the arithmetic.
        val splitRegistry = reg.withValue(ThresholdName.SYNC_FILE_SPLIT_BYTES, ThresholdName.SYNC_FILE_SPLIT_BYTES.floor)
        val records = (1..400).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") }
        val budget = splitRegistry[ThresholdName.SYNC_FILE_SPLIT_BYTES].toInt()
        val split = exporter.exportSplit("post7-ph1", records, key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL", splitRegistry)
        assertTrue(split.size > 1, "400 records must exceed a ${budget}-byte budget")
        for (bundle in split) assertTrue(bundle.sizeBytes <= budget, "${bundle.fileName} is ${bundle.sizeBytes} bytes")

        val diary = diary()
        var accepted = 0
        for (bundle in split) accepted += importer().import(bundle.bytes, key, diary, Fixtures.T0_MILLIS).accepted
        assertEquals(400, accepted)
        assertEquals(records.size, diary.size())
    }

    @Test
    fun aRecordThatCannotFitATransportFileFailsLoudlyRatherThanTruncating() {
        val splitRegistry = reg.withValue(ThresholdName.SYNC_FILE_SPLIT_BYTES, ThresholdName.SYNC_FILE_SPLIT_BYTES.floor)
        // One record bigger than a whole transport file. The only two possible answers are
        // "deliver it whole" and "fail"; truncating an embedding would deliver a record that
        // decodes into a plausible-looking face that is not the person.
        val huge = Fixtures.event(1, seq = 1, device = "post7-ph1", post = "P".repeat(budgetOf(splitRegistry)))
        val failure = assertFailsWith<BundleTooLargeException> {
            exporter.exportSplit("post7-ph1", listOf(huge), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL", splitRegistry)
        }
        assertTrue(failure.message!!.contains(huge.id), "the failure must name the record it could not carry")
    }

    private fun budgetOf(registry: ThresholdRegistry): Int =
        registry[ThresholdName.SYNC_FILE_SPLIT_BYTES].toInt()

    @Test
    fun watchlistPacksStayInsideTheQrBudget() {
        val entries = (1..6).map { index ->
            WatchlistEntry(
                id = Ulid.watchlistId(Fixtures.T0_MILLIS + index, Random(index)),
                emb = ByteArray(128) { (it + index).toByte() },
                qScale = 0.007874016f,
                label = "WANTED: subject $index",
                source = "HQ-2026-000$index",
                expiresIso = IsoInstant.format(Fixtures.T0_MILLIS + 86_400_000L),
            )
        }
        val pack = exporter.watchlistPack(entries, key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, reg)
        assertTrue(pack.sizeBytes <= reg[ThresholdName.QR_PACKET_MAX_BYTES].toInt())
        assertEquals("kasoti_watchlist.json", pack.fileName)

        val read = WatchlistPackReader.read(pack.bytes, key, mac, Fixtures.EMB_MODEL, Fixtures.T0_MILLIS)
        assertEquals(WatchlistOutcome.ACCEPTED, read.outcome)
        assertEquals(entries, read.entries)
        assertTrue(read.expired.isEmpty())
    }

    @Test
    fun anOversizedWatchlistPackThrowsInsteadOfTruncating() {
        val many = (1..40).map { index ->
            WatchlistEntry(
                id = Ulid.watchlistId(Fixtures.T0_MILLIS + index, Random(index)),
                emb = ByteArray(128) { index.toByte() },
                qScale = 1f,
                label = "WANTED: subject number $index of a very long list",
                source = "HQ-2026-$index",
                expiresIso = IsoInstant.format(Fixtures.T0_MILLIS),
            )
        }
        val failure = assertFailsWith<WatchlistPackTooLargeException> {
            exporter.watchlistPack(many, key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, reg)
        }
        assertTrue(failure.message!!.contains("QR budget"))
    }

    @Test
    fun anOverlongWatchlistLabelIsRejectedAtBuildTime() {
        val limit = reg[ThresholdName.WATCHLIST_LABEL_MAX_CHARS].toInt()
        val entry = WatchlistEntry(
            id = Ulid.watchlistId(Fixtures.T0_MILLIS, Random(1)),
            emb = ByteArray(128),
            qScale = 1f,
            label = "W".repeat(limit + 1),
            source = "HQ-2026-0001",
            expiresIso = IsoInstant.format(Fixtures.T0_MILLIS),
        )
        assertFailsWith<IllegalArgumentException> {
            exporter.watchlistPack(listOf(entry), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, reg)
        }
    }

    @Test
    fun aTamperedWatchlistPackIsRejectedAndAForeignGenerationIsQuarantined() {
        val entry = WatchlistEntry(
            id = Ulid.watchlistId(Fixtures.T0_MILLIS, Random(1)),
            emb = ByteArray(128) { 7 },
            qScale = 1f,
            label = "WANTED: test",
            source = "HQ-2026-0001",
            expiresIso = IsoInstant.format(Fixtures.T0_MILLIS - 86_400_000L),
        )
        val pack = exporter.watchlistPack(listOf(entry), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, reg)

        val tampered = pack.bytes.copyOf()
        val macAt = String(tampered, Charsets.UTF_8).indexOf("\"hmac\":\"") + HMAC_FIELD_OFFSET
        tampered[macAt] = (tampered[macAt].toInt() xor 0x01).toByte()
        assertEquals(
            WatchlistOutcome.REJECTED_HMAC,
            WatchlistPackReader.read(tampered, key, mac, Fixtures.EMB_MODEL, Fixtures.T0_MILLIS).outcome,
        )

        assertEquals(
            WatchlistOutcome.QUARANTINED_MODEL_MISMATCH,
            WatchlistPackReader.read(pack.bytes, key, mac, Fixtures.OTHER_EMB_MODEL, Fixtures.T0_MILLIS).outcome,
        )

        val read = WatchlistPackReader.read(pack.bytes, key, mac, Fixtures.EMB_MODEL, Fixtures.T0_MILLIS)
        assertEquals(1, read.expired.size, "an expired watchlist entry must be reported, not silently used")
    }

    // --- performance budget ---------------------------------------------------------------------------------

    @Test
    fun perfOneHundredThousandRecordMergeStaysInsideTheSyncBudget() {
        // Imported the way SYNC.md §5 actually delivers it: many transport-sized bundles, not one
        // enormous document. A single 45 MB file would need a streaming parser, which is a P1 item.
        //
        // Fixture construction is outside the measurement; the budget covers the protocol work —
        // canonicalise, MAC, parse, replay-filter, merge, recompute rules — which is what SYNC.md
        // §8 is about. The digest is an injected non-crypto stand-in, so this number is a floor on
        // real hardware rather than a promise about it.
        val bundles = ArrayList<SyncBundle>(PERF_BUNDLES)
        var canonicalChars = 0L
        for (batch in 0 until PERF_BUNDLES) {
            val first = batch * PERF_RECORDS_PER_BUNDLE + 1
            val records = (first..first + PERF_RECORDS_PER_BUNDLE - 1).map { index ->
                // One device, one monotonic sequence counter, crossings spread over a set of
                // posts. Both matter: a bundle whose records carry sequence numbers that do not
                // rise with their own timestamps is not something a device produces, and the
                // replay guard is right to reject it.
                Fixtures.event(
                    index = index,
                    seq = index.toLong(),
                    device = PERF_DEVICE,
                    post = "POST${index % PERF_POSTS}",
                    ts = IsoInstant.format(Fixtures.T0_MILLIS + (index.toLong() / PERF_POSTS) * PERF_GATE_INTERVAL_MS),
                )
            }
            bundles += exporter.export(PERF_DEVICE, records, key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
            canonicalChars += bundles.last().sizeBytes.toLong()
        }

        // Warm-up pass over the first bundles. The budget is about steady-state merge
        // throughput, and a cold JVM interprets this code for the first few thousand records,
        // which is a property of the measurement rather than of the protocol. The warm-up goes
        // into a throwaway diary so nothing is double-counted.
        val scratch = diary()
        for (bundle in bundles.take(PERF_WARMUP_BUNDLES)) importer().import(bundle.text, key, scratch, Fixtures.T0_MILLIS)
        check(scratch.size() == PERF_WARMUP_BUNDLES * PERF_RECORDS_PER_BUNDLE)

        val diary = diary()
        val importMillis = measureTimeMillis {
            for (bundle in bundles) {
                val report = importer().import(bundle.text, key, diary, Fixtures.T0_MILLIS)
                assertEquals(PERF_RECORDS_PER_BUNDLE, report.accepted, "${bundle.fileName}")
            }
        }
        println(
            "PERF kasoti-sync/1 merge of $PERF_RECORDS records in $PERF_BUNDLES bundles " +
                "across $PERF_POSTS posts ($canonicalChars bundle bytes): $importMillis ms " +
                "(SYNC.md §8 budget <5 s desktop / <15 s phone; injected non-crypto digest)",
        )
        assertTrue(
            importMillis < PERF_BUDGET_MS,
            "merging $PERF_RECORDS records took $importMillis ms, over the SYNC.md §8 phone budget",
        )
        assertEquals(PERF_RECORDS, diary.size())
        assertTrue(diary.verifyChain().valid, "100k links must still verify")
    }

    // --- json codec -------------------------------------------------------------------------------------------

    @Test
    fun base64RoundTripsAndRejectsRubbish() {
        for (size in 0..200) {
            val bytes = ByteArray(size) { (it * 37 - 128).toByte() }
            val text = Base64Codec.encode(bytes)
            assertEquals(text, Base64Codec.encode(bytes), "encoding must be deterministic")
            assertContentEquals(bytes, assertNotNull(Base64Codec.decodeOrNull(text)))
        }
        assertEquals("AA==", Base64Codec.encode(byteArrayOf(0)))
        assertEquals("AAA=", Base64Codec.encode(byteArrayOf(0, 0)))
        for (bad in listOf("A", "AAAAA", "A===", "!!!!", "AA=A", "AAAA ")) {
            assertNull(Base64Codec.decodeOrNull(bad), "should reject '$bad'")
        }
    }

    @Test
    fun canonicalOutputIsIndependentOfInputKeyOrder() {
        val a = """{"z":1,"a":{"n":2,"m":3},"list":[{"y":1,"x":2}]}"""
        val b = """{"list":[{"x":2,"y":1}],"a":{"m":3,"n":2},"z":1}"""
        assertEquals(CanonicalJson.write(assertNotNull(JsonParser.parseOrNull(a))), CanonicalJson.write(assertNotNull(JsonParser.parseOrNull(b))))
    }

    @Test
    fun sequenceRangeMustBeOrdered() {
        assertFailsWith<IllegalArgumentException> {
            SyncEnvelope(1, "k1", "post7-ph1", 9, 2, Fixtures.T0, Fixtures.EMB_MODEL, 1f, emptyList(), "")
        }
        assertFailsWith<IllegalArgumentException> {
            SyncEnvelope(2, "k1", "post7-ph1", 1, 2, Fixtures.T0, Fixtures.EMB_MODEL, 1f, emptyList(), "")
        }
    }

    @Test
    fun aDiaryLineIsTheSameBytesAsASyncRecord() {
        val event = Fixtures.event(1, seq = 1, device = "post7-ph1")
        val bundle = exporter.export("post7-ph1", listOf(event), key, Fixtures.T0_MILLIS, Fixtures.EMB_MODEL, "RAXAUL")
        val record = assertNotNull(SyncEnvelope.decode(bundle.bytes)).records.single()
        assertEquals(event.canonical(), record, "one serialisation, so a diary line IS a sync record")
        assertEquals(event, CrossingEvent.decode(record))
        val parsed = JsonParser.parse(record) as JsonValue.Obj
        assertEquals(JsonValue.Str("cross"), parsed.fields["t"])
    }

    private companion object {
        /** Byte offset from the start of `"hmac":"` to its first hex character. */
        const val HMAC_FIELD_OFFSET = 8

        const val PERF_RECORDS = 100_000
        const val PERF_BUNDLES = 20
        const val PERF_WARMUP_BUNDLES = 2
        const val PERF_DEVICE = "post7-ph1"
        const val PERF_POSTS = 20

        /** Twelve crossings an hour at each gate: busy, but a busy gate and not a city wall. */
        const val PERF_GATE_INTERVAL_MS = 300_000L
        const val PERF_RECORDS_PER_BUNDLE = PERF_RECORDS / PERF_BUNDLES

        /**
         * SYNC.md §8: under 5 s on the reference desktop, 15 s on a phone.
         *
         * The hard gate is the phone budget, because this suite runs on whatever machine CI
         * happens to be, which is routinely slower than the reference desktop and is frequently
         * shared. The measured value is printed above so the desktop number stays visible; the
         * repo owner can tighten this once a reference machine is pinned.
         */
        const val PERF_BUDGET_MS = 15_000L
    }
}
