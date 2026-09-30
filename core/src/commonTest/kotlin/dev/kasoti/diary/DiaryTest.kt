package dev.kasoti.diary

import dev.kasoti.crypto.Hex
import dev.kasoti.face.FaceMath
import dev.kasoti.fusion.DiaryHit
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.Verdict
import dev.kasoti.json.CanonicalJson
import dev.kasoti.json.JsonParser
import dev.kasoti.json.JsonValue
import dev.kasoti.threshold.ThresholdName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Diary tests: storage layout, merge determinism, retention and the three rules.
 *
 * Every rule test includes a negative case. A detection rule that has only been shown to fire
 * is not tested — a rule that fires on everything is indistinguishable from a working rule until
 * someone tries to use it.
 */
class DiaryTest {

    private fun diary(
        storage: InMemoryDiaryStorage = InMemoryDiaryStorage(),
        digest: dev.kasoti.crypto.Digest = FakeDigest(),
        model: String = Fixtures.EMB_MODEL,
    ) = FileDiary(storage, digest, model)

    // --- record shape --------------------------------------------------------------------

    @Test
    fun crossingEventRoundTripsThroughCanonicalJson() {
        val event = Fixtures.event(1, nameSha = "a".repeat(64), dob = "1990-12-31")
        val text = event.canonical()
        assertEquals(event, CrossingEvent.decode(text))
        assertEquals(text, CrossingEvent.decode(text)!!.canonical(), "canonical form must be a fixed point")
    }

    @Test
    fun canonicalFormIsSortedAndWhitespaceFree() {
        val text = Fixtures.event(2).canonical()
        assertFalse(text.contains(" "), "canonical JSON carries no whitespace: $text")
        val keys = Regex("\"([a-zA-Z]+)\":").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(keys.sorted(), keys, "object keys must be emitted in sorted order")
        assertTrue(keys.contains("t"), "SYNC.md §3 requires a record type discriminator")
    }

    @Test
    fun unknownFieldsSurviveARoundTrip() {
        val base = Fixtures.event(3).canonical()
        // Inserted in sorted position: the sender's bytes are canonical, so the round trip has
        // to be byte-identical or the sender's HMAC stops verifying on our side.
        val withExtra = base.replace("\"id\":", "\"futureField\":{\"nested\":[1,2.5,null,true]},\"id\":")
        val decoded = assertNotNull(CrossingEvent.decode(withExtra))
        assertEquals(1, decoded.extra.size)
        assertEquals(
            withExtra,
            decoded.canonical(),
            "an unknown field must round-trip byte-for-byte or the sender's HMAC stops verifying",
        )
    }

    @Test
    fun knownFieldsCannotBeShadowedByUnknownOnes() {
        // `extra` is writable, so a caller could try to smuggle a second "post" past the
        // canonical writer. It must not: a claim the diary does not own may not overwrite one it does.
        val event = Fixtures.event(4).copy(extra = mapOf("post" to JsonValue.Str("ATTACKER")))
        assertEquals("RAXAUL", CrossingEvent.decode(event.canonical())!!.post)
    }

    @Test
    fun malformedRecordsDecodeToNullRatherThanThrowing() {
        val good = Fixtures.event(5).canonical()
        val cases = listOf(
            "",
            "null",
            "[]",
            "{",
            good.dropLast(1) + ",\"seq\":\"not-a-number\"}}",
            good.dropLast(1) + ",\"emb\":\"!!!not base64!!!\"}}",
            good.dropLast(1) + ",\"verdict\":\"CHERRY_PICKED\"}}",
            good.dropLast(1) + ",\"findings\":[\"NOT_A_CODE\"]}}",
            good.replace("\"q\":", "\"q\":") .replace(Regex("\"q\":[^,]*,"), "\"q\":9.5,"),
            good.replace("\"ts\":\"[^\"]*\"".toRegex(), "\"ts\":\"yesterday\""),
            good.replace("\"id\":\"[^\"]*\"".toRegex(), "\"id\":\"evt_nope\""),
        )
        for (case in cases) {
            assertNull(CrossingEvent.decode(case), "should not decode: ${case.take(60)}")
        }
    }

    @Test
    fun duplicateJsonKeysAreRejected() {
        assertNull(JsonParser.parseObjectOrNull("{\"a\":1,\"a\":2}"))
    }

    @Test
    fun excessivelyNestedJsonIsRejectedRatherThanOverflowingTheStack() {
        val deep = "[".repeat(500) + "]".repeat(500)
        assertNull(JsonParser.parseOrNull(deep))
    }

    @Test
    fun nonFiniteAndMalformedNumbersAreRejected() {
        for (bad in listOf("NaN", "Infinity", "+1", "01", "1.", ".5", "1e", "-")) {
            assertNull(JsonParser.parseOrNull(bad), "should reject $bad")
        }
        assertEquals(JsonValue.Num(1), JsonParser.parse("1"))
        assertEquals(JsonValue.Num(-42), JsonParser.parse("-42"))
    }

    @Test
    fun stringsRoundTripEscapesAndUnicode() {
        val original = "नाम: \"quoted\"\\slash/\b\u000C\n\r\tend"
        val text = CanonicalJson.write(JsonValue.Obj(mapOf("k" to JsonValue.Str(original))))
        assertEquals(original, JsonParser.parse(text).let { (it as JsonValue.Obj).fields["k"] }?.let { (it as JsonValue.Str).value })
    }

    @Test
    fun nonAsciiIsEmittedAsRawUtf8NotEscaped() {
        val text = CanonicalJson.write(JsonValue.Str("राजस्थान"))
        assertEquals("\"राजस्थान\"", text)
    }

    // --- append, chain, generation ---------------------------------------------------------

    @Test
    fun appendAssignsMonotonicSequencePerDeviceAndExtendsTheChain() {
        val store = InMemoryDiaryStorage()
        val log = diary(store)
        val first = Fixtures.event(1, seq = 0, device = "post3-ph1")
        val second = Fixtures.event(2, seq = 0, device = "post3-ph1")
        val other = Fixtures.event(3, seq = 0, device = "post7-ph1")

        log.append(first)
        log.append(second)
        log.append(other)

        assertEquals(2L, log.maxSeq("post3-ph1"))
        assertEquals(1L, log.maxSeq("post7-ph1"), "sequence counters are per device, not global")
        assertEquals(3, log.size())
        assertTrue(log.verifyChain().valid)
        assertEquals(3, store.flushCount, "every append must reach the disk before returning")
    }

    @Test
    fun appendRejectsANonMonotonicSequence() {
        val log = diary()
        log.append(Fixtures.event(1, seq = 0))
        assertFailsWith<DiarySequenceException> { log.append(Fixtures.event(2, seq = 9)) }
    }

    @Test
    fun appendRefusesAForeignEmbeddingGeneration() {
        val log = diary()
        val foreign = Fixtures.event(1, embModel = Fixtures.OTHER_EMB_MODEL)
        assertFailsWith<DiaryModelMismatchException> { log.append(foreign) }
        assertEquals(0, log.size())
    }

    @Test
    fun anEventIsNotComparableAcrossGenerations() {
        val query = Fixtures.embeddingTag(Fixtures.embedding(1), Fixtures.OTHER_EMB_MODEL)
        assertTrue(diary().search(query, 5).isEmpty(), "a foreign generation must yield no hits at all")
    }

    @Test
    fun aMisalignedVectorFileIsRefusedNotSilentlySearched() {
        val store = InMemoryDiaryStorage()
        val log = diary(store)
        log.append(Fixtures.event(1, seq = 0))
        // Simulate a truncated embeddings.bin: one record, one short vector.
        store.replaceAll(listOf(Fixtures.event(2).canonical()), listOf(ByteArray(64)))
        val reloaded = diary(store)
        assertFailsWith<DiaryCorruptException> { reloaded.all() }
    }

    // --- search ------------------------------------------------------------------------------

    @Test
    fun searchRanksByCosineAndReturnsTypedHits() {
        val log = diary()
        val target = Fixtures.embedding(7)
        log.append(Fixtures.event(1, seq = 0, emb = Fixtures.embedding(2)))
        log.append(Fixtures.event(2, seq = 0, emb = target))
        log.append(Fixtures.event(3, seq = 0, emb = Fixtures.embedding(3)))

        val hits = log.search(Fixtures.embeddingTag(target), 3)
        assertEquals(3, hits.size)
        val best = assertNotNull(hits.firstOrNull())
        assertTrue(best.similarity > 0.99f, "an exact match must score ~1.0, got ${best.similarity}")
        assertTrue(hits[0].similarity >= hits[1].similarity, "hits must be ranked best-first")
        assertTrue(hits.zipWithNext().all { (a, b) -> a.similarity >= b.similarity })
    }

    @Test
    fun dequantiseUsesTheStoredScale() {
        // Two embeddings an order of magnitude apart must both come back intact. Cosine is
        // scale-invariant so it cannot catch a wrong qScale; only the dequantised vector can,
        // which is why this asserts on `dequantise` rather than on a similarity.
        val quiet = FloatArray(128).also { it[0] = 0.02f }
        val loud = FloatArray(128).also { it[0] = 40f }
        for (raw in listOf(quiet, loud)) {
            val normalised = FaceMath.l2Normalize(raw)
            val quantised = QuantisedEmbedding.of(normalised)
            val restored = quantised.dequantise()
            var maxError = 0f
            for (i in normalised.indices) maxError = maxOf(maxError, kotlin.math.abs(normalised[i] - restored[i]))
            assertTrue(maxError < 0.01f, "dequantisation must honour qScale=${
                quantised.qScale
            }, maxError $maxError")
        }
    }

    @Test
    fun diarySearchReportsTheRunnerUpMargin() {
        val log = diary()
        val base = Fixtures.embedding(11)
        val near = base.copyOf().also { it[0] += 0.05f }
        log.append(Fixtures.event(1, seq = 0, emb = base).copy(nameSha = "a".repeat(64)))
        log.append(Fixtures.event(2, seq = 0, emb = FaceMath.l2Normalize(near)).copy(nameSha = "b".repeat(64)))

        val crowded = DiarySearch.search(log, Fixtures.embeddingTag(base), Fixtures.REGISTRY)
        assertFalse(crowded.actionableAlone, "two near-identical candidates must not be actionable alone")
        assertEquals(
            assertNotNull(crowded.top).similarity - assertNotNull(crowded.runnerUp).similarity,
            crowded.margin,
            "the margin must be reported, not merely implied",
        )
        assertTrue(
            crowded.margin < Fixtures.REGISTRY[ThresholdName.DELTA_MARGIN].toFloat(),
            "the near-duplicate candidate must fall inside the delta margin",
        )

        val lone = diary()
        lone.append(Fixtures.event(1, seq = 0, emb = base))
        val alone = DiarySearch.search(lone, Fixtures.embeddingTag(base), Fixtures.REGISTRY)
        assertTrue(alone.actionableAlone, "with no competitor the top hit stands on its own")
        assertEquals(DiarySearchResult.NO_COMPETITOR, alone.margin)
    }

    @Test
    fun diarySearchSuppressesAForeignGeneration() {
        val result = DiarySearch.search(
            diary(),
            Fixtures.embeddingTag(Fixtures.embedding(1), Fixtures.OTHER_EMB_MODEL),
            Fixtures.REGISTRY,
        )
        assertEquals(SearchSuppression.EMB_MODEL_MISMATCH, result.suppression)
        assertFalse(result.actionableAlone)
        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun watchlistSearchAppliesItsOwnFloor() {
        val log = diary()
        log.append(Fixtures.event(1, seq = 0, emb = Fixtures.embedding(4)))
        val strangers = DiarySearch.watchlist(log, Fixtures.embeddingTag(Fixtures.embedding(9)), Fixtures.REGISTRY)
        assertTrue(strangers.hits.none { it.similarity >= Fixtures.REGISTRY[ThresholdName.T_WL].toFloat() })
    }

    // --- merge -------------------------------------------------------------------------------

    @Test
    fun mergeIsIdempotent() {
        val log = diary()
        val incoming = (1..5).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") }
        val first = log.mergeIncoming(incoming)
        assertEquals(5, first.accepted)
        val tip = log.tip()

        val second = log.mergeIncoming(incoming)
        assertEquals(0, second.accepted)
        assertEquals(5, second.dupes)
        assertEquals(5, log.size(), "merging twice must not duplicate")
        assertEquals(tip, log.tip(), "an idempotent merge must not move the tip")
    }

    @Test
    fun mergeIsDeterministicAcrossInputOrders() {
        val base = (1..25).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") }
        val orders = listOf(
            base.shuffled(java.util.Random(1)),
            base.shuffled(java.util.Random(2)),
            base.reversed(),
            base.sortedByDescending { it.ts },
        )
        val tips = orders.map { order ->
            val log = diary()
            log.mergeIncoming(order)
            log.tip()
        }
        assertEquals(1, tips.distinct().size, "the same set must yield the same tip regardless of arrival order: $tips")
    }

    @Test
    fun mergeSkipsIdsAlreadyHeldAndDuplicatesInsideTheBatch() {
        val log = diary()
        log.mergeIncoming(listOf(Fixtures.event(1, seq = 1, device = "post7-ph1")))
        val again = Fixtures.event(1, seq = 1, device = "post7-ph1")
        val fresh = Fixtures.event(2, seq = 2, device = "post7-ph1")
        val report = log.mergeIncoming(listOf(again, fresh, fresh))
        assertEquals(1, report.accepted)
        assertEquals(2, report.dupes)
        assertEquals(2, log.size())
    }

    @Test
    fun mergeQuarantinesTheWholeBatchOnAGenerationMismatch() {
        val log = diary()
        val quarantine = InMemoryQuarantine()
        val mixed = listOf(
            Fixtures.event(1, seq = 1, device = "post7-ph1"),
            Fixtures.event(2, seq = 2, device = "post7-ph1", embModel = Fixtures.OTHER_EMB_MODEL),
        )
        val report = log.mergeIncoming(mixed, quarantine)
        assertEquals(0, report.accepted)
        assertEquals(2, report.quarantined)
        assertEquals(0, log.size(), "a generation mismatch must never land partially")
        assertEquals(QuarantineReason.EMB_MODEL_MISMATCH, quarantine.batches.single().reason)
        assertEquals(2, quarantine.recordCount, "the whole batch is held, not just the odd record")
    }

    @Test
    fun mergeRejectsRecordsWithoutASequence() {
        val log = diary()
        val report = log.mergeIncoming(listOf(Fixtures.event(1, seq = 0, device = "post7-ph1")))
        assertEquals(0, report.accepted)
        assertEquals(1, report.rejected)
    }

    @Test
    fun reanchorsTheChainOnMergeSoTheLocalTipIsLocal() {
        val log = diary()
        val incoming = Fixtures.event(9, seq = 1, device = "post7-ph1", prev = "deadbeef".repeat(8))
        log.mergeIncoming(listOf(incoming))
        val stored = assertNotNull(log.get(incoming.id))
        assertEquals(log.tip().let { stored.prev }, stored.prev)
        assertTrue(log.verifyChain().valid, "a merged record must link into the local chain")
    }

    @Test
    fun mergeKeepsTheVectorFileIndexAlignedWithTheJsonl() {
        val store = InMemoryDiaryStorage()
        val log = diary(store)
        val incoming = (1..4).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") }
        log.mergeIncoming(incoming)
        assertEquals(store.readLines().size, store.readVectors().size)
        assertEquals(4 * 128, store.vectorBytes().size)
        for (index in 1..4) {
            assertEquals(incoming[index - 1].emb.toList(), store.readVectors()[index - 1].toList())
        }
    }

    // --- retention ---------------------------------------------------------------------------

    @Test
    fun purgeRemovesOldRecordsLocallyAndRewritesBothFiles() {
        val store = InMemoryDiaryStorage()
        val log = diary(store)
        repeat(5) { index -> log.append(Fixtures.event(index, seq = 0, ts = IsoInstant.format(Fixtures.T0_MILLIS + index * 3_600_000L))) }

        val cutoff = IsoInstant.format(Fixtures.T0_MILLIS + 2 * 3_600_000L)
        assertEquals(2, log.purge(cutoff))
        assertEquals(3, log.size())
        assertEquals(3, store.readLines().size)
        assertEquals(3, store.readVectors().size)
        assertTrue(log.all().none { it.ts == IsoInstant.format(Fixtures.T0_MILLIS) }, "the oldest record is gone")
    }

    @Test
    fun purgeDoesNotPropagateThroughAMerge() {
        val local = diary()
        val remote = diary()
        val batch = (1..4).map { Fixtures.event(it, seq = it.toLong(), device = "post7-ph1") }
        local.mergeIncoming(batch)

        local.purge(IsoInstant.format(Fixtures.T0_MILLIS + 10 * 86_400_000L))
        assertEquals(0, local.size())

        // The sender still holds the records and its next bundle still carries them: deletes are
        // local-only (SYNC.md §1), so they come back. That is the privacy trade, made visible.
        val again = local.mergeIncoming(batch)
        assertEquals(4, again.accepted)
        assertEquals(4, local.size())
    }

    @Test
    fun purgeKeepsEverythingNewerThanTheCutoffIncludingTheBoundary() {
        val log = diary()
        repeat(4) { index -> log.append(Fixtures.event(index, seq = 0, ts = IsoInstant.format(Fixtures.T0_MILLIS + index * 3_600_000L))) }
        assertEquals(3, log.purge(IsoInstant.format(Fixtures.T0_MILLIS + 3 * 3_600_000L)), "the cutoff itself is kept")
        assertEquals(1, log.size())
        assertEquals(Fixtures.event(3).post, log.all().single().post)
    }

    @Test
    fun purgeRefusesAnUnreadableCutoff() {
        val log = diary()
        log.append(Fixtures.event(1, seq = 0))
        assertFailsWith<IllegalArgumentException> { log.purge("last tuesday") }
        assertEquals(1, log.size())
    }

    @Test
    fun wipeClearsEverythingIncludingSequenceCounters() {
        val log = diary()
        log.append(Fixtures.event(1, seq = 0))
        log.wipe()
        assertEquals(0, log.size())
        assertEquals(0L, log.maxSeq("post3-ph1"))
        assertEquals(genesisOf(diary()), log.tip())
        assertTrue(log.verifyChain().valid)
    }

    // --- rule: alias ---------------------------------------------------------------------------

    @Test
    fun aliasRuleFiresOnAContradictedIdentity() {
        val log = diary()
        val target = Fixtures.embedding(21)
        log.append(Fixtures.event(1, seq = 0, emb = target, nameSha = "b".repeat(64), dob = "1970-01-01"))

        val result = DiarySearch.search(log, Fixtures.embeddingTag(target), Fixtures.REGISTRY)
        val flags = aliasRule(result.hits, claimedNameSha = "a".repeat(64), claimedDob = "1990-05-05", reg = Fixtures.REGISTRY)

        val flag = assertNotNull(flags.firstOrNull())
        assertEquals(FindingCode.R_ALIAS_01, flag.finding.code)
        assertTrue(flag.nameDiffers)
        assertTrue(flag.dobDiffers)
        assertEquals(listOf(flag.eventId), flag.eventIds, "evidence must name the contributing event")
        assertTrue(flag.requiresSupervisorConfirmation, "an alias is a candidate, never a silent RED")
    }

    @Test
    fun aliasRuleFiresOnADobOnlyContradiction() {
        val log = diary()
        val target = Fixtures.embedding(22)
        val sameName = "a".repeat(64)
        log.append(Fixtures.event(1, seq = 0, emb = target, nameSha = sameName, dob = "1970-01-01"))

        val hits = DiarySearch.search(log, Fixtures.embeddingTag(target), Fixtures.REGISTRY).hits
        val flag = assertNotNull(aliasRule(hits, sameName, "1990-05-05", Fixtures.REGISTRY).firstOrNull())
        assertFalse(flag.nameDiffers)
        assertTrue(flag.dobDiffers)
    }

    @Test
    fun aliasRuleStaysQuietWhenTheClaimMatchesAndBelowTheFloor() {
        val log = diary()
        val target = Fixtures.embedding(23)
        val sameName = "a".repeat(64)
        log.append(Fixtures.event(1, seq = 0, emb = target, nameSha = sameName, dob = "1990-05-05"))

        val hits = DiarySearch.search(log, Fixtures.embeddingTag(target), Fixtures.REGISTRY).hits
        assertTrue(aliasRule(hits, sameName, "1990-05-05", Fixtures.REGISTRY).isEmpty(), "an agreeing identity is not an alias")

        val strangers = DiarySearch.search(log, Fixtures.embeddingTag(Fixtures.embedding(99)), Fixtures.REGISTRY).hits
        assertTrue(aliasRule(strangers, sameName, "1990-05-05", Fixtures.REGISTRY).isEmpty(), "a weak match is not an alias")
    }

    @Test
    fun aliasRuleSkipsTheDobArmWhenTheDeploymentHashesDob() {
        val log = diary()
        val target = Fixtures.embedding(24)
        log.append(Fixtures.event(1, seq = 0, emb = target, nameSha = "b".repeat(64), dob = null))
        val hits = DiarySearch.search(log, Fixtures.embeddingTag(target), Fixtures.REGISTRY).hits
        assertTrue(aliasRule(hits, "b".repeat(64), null, Fixtures.REGISTRY).isEmpty(), "an unevaluable check must not read as a pass")
    }

    @Test
    fun aliasRuleIsBoundedAndDeterministicallyOrdered() {
        val hits = (1..9).map { index ->
            DiaryHit(
                eventId = "evt_%026d".format(index),
                similarity = 0.95f - index * 0.001f,
                post = "RAXAUL",
                timestamp = Fixtures.T0,
                nameHash = "z".repeat(64),
                dob = "1990-01-01",
            )
        }
        val flags = aliasRule(hits, "y".repeat(64), "1990-01-01", Fixtures.REGISTRY)
        val limit = Fixtures.REGISTRY[ThresholdName.ALIAS_MAX_FLAGS].toInt()
        assertEquals(limit, flags.size)
        assertEquals(flags.map { it.similarity }.sortedDescending(), flags.map { it.similarity })
        assertEquals(flags.map { it.eventId }, aliasRule(hits.reversed(), "y".repeat(64), "1990-01-01", Fixtures.REGISTRY).map { it.eventId })
    }

    // --- rule: travel ---------------------------------------------------------------------------

    @Test
    fun impossibleTravelFiresAboveTheSpeedLimitOnly() {
        val posts = Fixtures.postMap()
        val far = listOf(
            Fixtures.event(1, seq = 0, device = "a", post = "RAXAUL", ts = "2026-03-14T09:00:00.000Z"),
            // Raxaul→Guna is ~640 km; eight hours is ~80 km/h, which is legal.
            Fixtures.event(2, seq = 0, device = "a", post = "GUNA", ts = "2026-03-14T17:00:00.000Z"),
        )
        assertTrue(impossibleTravel(far, posts, Fixtures.REGISTRY).isEmpty())

        val impossible = listOf(
            Fixtures.event(1, seq = 0, device = "a", post = "RAXAUL", ts = "2026-03-14T09:00:00.000Z"),
            // ~640 km in one hour: far above the 120 km/h policy ceiling.
            Fixtures.event(2, seq = 0, device = "a", post = "GUNA", ts = "2026-03-14T10:00:00.000Z"),
        )
        val flags = impossibleTravel(impossible, posts, Fixtures.REGISTRY)
        val flag = assertNotNull(flags.firstOrNull())
        assertEquals("RAXAUL", flag.fromPost)
        assertEquals("GUNA", flag.toPost)
        assertTrue(flag.impliedSpeedKmh > 120.0)
        assertTrue(flag.distanceKm > 500.0, "Raxaul to Guna is ~640 km, got ${flag.distanceKm}")
    }

    @Test
    fun impossibleTravelIgnoresSameTimestampsAndUnknownPosts() {
        val posts = Fixtures.postMap()
        val sameInstant = listOf(
            Fixtures.event(1, seq = 0, device = "a", post = "RAXAUL", ts = "2026-03-14T09:00:00.000Z"),
            Fixtures.event(2, seq = 0, device = "a", post = "GUNA", ts = "2026-03-14T09:00:00.000Z"),
        )
        assertTrue(
            impossibleTravel(sameInstant, posts, Fixtures.REGISTRY).isEmpty(),
            "zero elapsed time is clock skew, not a speed — firing on it would be a false RED",
        )

        val unknownPost = listOf(
            Fixtures.event(1, seq = 0, device = "a", post = "NOWHERE", ts = "2026-03-14T09:00:00.000Z"),
            Fixtures.event(2, seq = 0, device = "a", post = "GUNA", ts = "2026-03-14T09:05:00.000Z"),
        )
        assertTrue(impossibleTravel(unknownPost, posts, Fixtures.REGISTRY).isEmpty())
    }

    @Test
    fun haversineIsSane() {
        val posts = Fixtures.postMap()
        assertEquals(0.0, haversineKm(assertNotNull(posts["RAXAUL"]), assertNotNull(posts["RAXAUL"])))
        val raxaulToBhopal = haversineKm(assertNotNull(posts["RAXAUL"]), assertNotNull(posts["BHOPAL"]))
        assertTrue(raxaulToBhopal in 700.0..900.0, "Raxaul to Bhopal is roughly 800 km, got $raxaulToBhopal")
    }

    // --- rule: facilitator ------------------------------------------------------------------------

    @Test
    fun facilitatorRuleCountsRepeatedCompany() {
        // The anchor crosses twice a week apart; on each crossing the same four subjects are
        // beside it. Repeat company is the signature — see the rule's KDoc for why.
        val events = buildList {
            add(subject(1, "anchor", 0))
            add(subject(2, "anchor", 7 * DAY_HOURS))
            for (index in 0 until 4) {
                add(subject(10 + index, "traveller$index", 1 + index))
                add(subject(50 + index, "traveller$index", 7 * DAY_HOURS + 1 + index))
            }
        }
        val flags = facilitatorRule(events, Fixtures.REGISTRY)
        val anchorKey = subjectKeyOf(events.first(), cohortPrefix())
        val flag = assertNotNull(flags.firstOrNull { it.anchor == anchorKey })
        assertEquals(FindingCode.A_FAC_01, flag.finding.code)
        assertEquals(4, flag.distinctGroups, "four cohorts met twice")
        assertTrue(
            flag.eventIds.containsAll(events.take(2).map { it.id }),
            "evidence must include the anchor's own crossings, not only its partners'",
        )
        assertEquals(flag.groupKeys, flag.groupKeys.sorted())
        assertEquals(flag.eventIds, flag.eventIds.sorted())
        assertFalse(flag.evidenceTruncated)
        assertEquals(flags.size, flags.map { it.anchor }.distinct().size, "one flag per anchor, never two")
        assertEquals(
            flags.map { it.anchor to it.distinctGroups },
            facilitatorRule(events.reversed(), Fixtures.REGISTRY).map { it.anchor to it.distinctGroups },
            "the rule must not depend on input order",
        )
    }

    @Test
    fun facilitatorRuleIgnoresAOneOffCrowd() {
        // The same anchor, the same post, a different crowd every time. This is what an ordinary
        // commuter crossing from a busy gate looks like, and it must not be flagged.
        val events = buildList {
            add(subject(1, "anchor", 0))
            add(subject(2, "anchor", 7 * DAY_HOURS))
            for (index in 0 until 4) {
                add(subject(10 + index, "traveller$index", 1 + index))
                add(subject(60 + index, "stranger$index", 7 * DAY_HOURS + 1 + index))
            }
        }
        assertTrue(facilitatorRule(events, Fixtures.REGISTRY).isEmpty())
    }

    @Test
    fun facilitatorRuleIgnoresALoneRepeatedTraveller() {
        // The same subject crossing repeatedly, always alone: a commuter, not an organiser.
        val events = (1..6).map { index -> subject(index, "commuter", index) }
        assertTrue(facilitatorRule(events, Fixtures.REGISTRY).isEmpty())
    }

    @Test
    fun facilitatorRuleIgnoresASubjectSeenOnlyOnce() {
        // Every passer-by at a busy post is technically "in the company of strangers". Flagging
        // them all would spend a supervisor's attention on nothing.
        val events = (1..8).map { index -> subject(index, "stranger$index", index) }
        assertTrue(facilitatorRule(events, Fixtures.REGISTRY).isEmpty())
    }

    @Test
    fun facilitatorRuleIgnoresCohortsOutsideTheCoOccurrenceWindow() {
        val events = buildList {
            add(subject(1, "anchor", 0))
            add(subject(2, "anchor", 7 * DAY_HOURS))
            for (index in 0 until 4) {
                // Present on the second crossing only, so every meeting happens once.
                add(subject(10 + index, "traveller$index", 20 * DAY_HOURS))
            }
        }
        assertTrue(facilitatorRule(events, Fixtures.REGISTRY).isEmpty())
    }

    @Test
    fun facilitatorRuleBoundsItsEvidenceButNotItsCount() {
        val tight = Fixtures.REGISTRY.withValue(ThresholdName.FACILITATOR_MAX_EVIDENCE, 4.0)
        val events = buildList {
            add(subject(1, "anchor", 0))
            add(subject(2, "anchor", 7 * DAY_HOURS))
            for (index in 0 until 6) {
                add(subject(10 + index, "traveller$index", 1 + index))
                add(subject(60 + index, "traveller$index", 7 * DAY_HOURS + 1 + index))
            }
        }
        val flag = assertNotNull(facilitatorRule(events, tight).firstOrNull())
        assertEquals(6, flag.distinctGroups, "the count stays true")
        assertEquals(4, flag.groupKeys.size, "the evidence is capped")
        assertTrue(flag.evidenceTruncated)
    }

    @Test
    fun subjectKeyIsHashedAndFallsBackWhenNameShaIsAbsent() {
        val named = Fixtures.event(1, seq = 0, nameSha = "0123456789abcdef" + "0".repeat(48))
        assertEquals("0123", subjectKeyOf(named, 4))
        val docOnly = named.copy(nameSha = "", docHash = "aaaa" + "0".repeat(60))
        assertEquals("aaaa", subjectKeyOf(docOnly, 4), "a deployment that hashes the name falls back to the document hash")
        assertFailsWith<IllegalArgumentException> {
            named.copy(nameSha = "", docHash = "")
        }
    }

    @Test
    fun aNonHashedNameShaIsRefusedBecauseTheCohortRuleWouldDegenerate() {
        val event = Fixtures.event(1, seq = 0)
        assertFailsWith<IllegalArgumentException> { event.copy(nameSha = "not-a-digest") }
        assertFailsWith<IllegalArgumentException> { event.copy(docHash = "sha256-of-nothing") }
    }

    // --- adversarial shapes -------------------------------------------------------------------------

    @Test
    fun unicodeAndVeryLongFieldValuesSurviveStorageAndSearch() {
        val store = InMemoryDiaryStorage()
        val log = diary(store)
        val longPost = "पोस्ट-" + "ए".repeat(4000)
        val target = Fixtures.embedding(31)
        log.append(Fixtures.event(1, seq = 0, emb = target, post = longPost))

        val reloaded = diary(store)
        val stored = assertNotNull(reloaded.all().firstOrNull())
        assertEquals(longPost, stored.post)
        assertEquals(1, reloaded.search(Fixtures.embeddingTag(target), 1).size)
    }

    @Test
    fun controlCharactersInFieldValuesAreEscapedNotDropped() {
        val nasty = "RAX\u0000AUL\t\"\\"
        val log = diary()
        val event = Fixtures.event(1, seq = 0, post = nasty)
        log.append(event)
        val stored = assertNotNull(log.get(event.id))
        assertEquals(nasty, stored.post)
        assertContentEquals(event.emb, stored.emb)
    }

    @Test
    fun trackAndVerdictRoundTripThroughTheirEnumNames() {
        val event = Fixtures.event(1, seq = 0, track = Track.PASSPORT, verdict = Verdict.GREY)
        val decoded = assertNotNull(CrossingEvent.decode(event.canonical()))
        assertEquals(Track.PASSPORT, decoded.track)
        assertEquals(Verdict.GREY, decoded.verdict)
    }

    // --- time --------------------------------------------------------------------------------------

    @Test
    fun isoInstantParsesAndFormatsUtcOnly() {
        assertEquals(Fixtures.T0_MILLIS, IsoInstant.parse(Fixtures.T0))
        assertEquals(Fixtures.T0, IsoInstant.format(Fixtures.T0_MILLIS))
        assertEquals(
            IsoInstant.parse(Fixtures.T0),
            IsoInstant.parse("2026-03-14T14:30:00.000+05:30"),
            "an offset timestamp is the same instant",
        )
        for (bad in listOf("", "2026-03-14", "2026-13-14T09:00:00Z", "2026-03-14T24:00:00Z", "2026-03-14T09:60:00Z", "2026-03-14T09:00:00+99:00", "2026-03-14T09:00:00Z ")) {
            assertNull(IsoInstant.parse(bad), "should reject '$bad'")
        }
    }

    @Test
    fun isoInstantRoundTripsAcrossAYearBoundary() {
        val boundary = IsoInstant.parse("2025-12-31T23:59:59.999Z")!!
        assertEquals("2026-01-01T00:00:00.000Z", IsoInstant.format(boundary + 1))
    }

    @Test
    fun ulidIdsAreUniqueWithinAMillisecondAndSortByTime() {
        val source = IdSource(Fixtures.T0_MILLIS, kotlin.random.Random(1))
        val ids = List(50) { source.nextEventId(Fixtures.T0_MILLIS) }
        assertEquals(50, ids.distinct().size, "a stalled device clock must not reuse an id")
        assertEquals(ids.sorted(), ids, "same-millisecond ids are already in creation order")
        assertTrue(ids.all { Ulid.isWellFormed(it.removePrefix("evt_")) })
    }

    /** The cohort-key width the registry actually uses; the rule and the tests must agree. */
    private fun cohortPrefix(): Int =
        Fixtures.REGISTRY[ThresholdName.FACILITATOR_GROUP_PREFIX_HEX].toInt()

    /** A crossing by [label] at [hoursAfterT0], always at Raxaul on one device. */
    private fun subject(index: Int, label: String, hoursAfterT0: Int): CrossingEvent = Fixtures.event(
        index = index,
        seq = 0,
        device = "p3",
        post = "RAXAUL",
        ts = IsoInstant.format(Fixtures.T0_MILLIS + hoursAfterT0 * 3_600_000L),
        nameSha = sha(label),
    )

    private fun genesisOf(log: Diary): String {
        val empty = FileDiary(InMemoryDiaryStorage(), FakeDigest(), log.embeddingGeneration())
        return empty.tip()
    }

    private fun sha(label: String): String = Hex.encode(FakeDigest().sha256(label.toByteArray(Charsets.UTF_8)))

    @Test
    fun quantisationRoundTripsWithinTheInt8ErrorBudget() {
        val original = Fixtures.embedding(41)
        val quantised = QuantisedEmbedding.of(original)
        val restored = quantised.dequantise()
        var maxError = 0f
        for (i in original.indices) maxError = maxOf(maxError, kotlin.math.abs(original[i] - restored[i]))
        assertTrue(maxError < 0.01f, "int8 quantisation error must stay far below a threshold step, got $maxError")
        assertTrue(cosineQuantised(original, quantised.bytes, quantised.qScale) > 0.999f)
    }

    @Test
    fun theSameDiaryAlwaysAnswersTheSameQueryTheSameWay() {
        val log = diary()
        repeat(20) { index -> log.append(Fixtures.event(index, seq = 0)) }
        val query = Fixtures.embedding(5)
        val first = log.search(Fixtures.embeddingTag(query), 7)
        repeat(5) { assertEquals(first.map { it.eventId }, log.search(Fixtures.embeddingTag(query), 7).map { it.eventId }) }
        assertSame(Fixtures.EMB_MODEL, log.embeddingGeneration())
    }

    private companion object {
        const val DAY_HOURS = 24
    }
}
