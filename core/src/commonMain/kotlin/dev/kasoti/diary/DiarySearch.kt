package dev.kasoti.diary

import dev.kasoti.face.Embedding
import dev.kasoti.fusion.DiaryHit
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry

/**
 * Top-K diary search *plus the runner-up margin* (DESIGN.md §5).
 *
 * The margin is the whole reason this type exists. A single similarity of 0.81 from a diary
 * that contains a thousand people is not evidence of an alias; it is evidence that the diary
 * has a thousand people and the best of them scores 0.81. The only thing that separates "this
 * is the same person" from "this is the least-bad of a large crowd" is how far ahead of the
 * second candidate the top one is.
 *
 * So the rule is stated as: a hit is *actionable alone* when it beats the runner-up by at least
 * [ThresholdName.DELTA_MARGIN]. Otherwise it is still reported — never dropped, because the
 * officer needs to see the candidate — but flagged as needing the next candidate disambiguated.
 * With fewer than two hits there is no competitor and the top hit stands on its own.
 */
data class DiarySearchResult(
    val hits: List<DiaryHit>,
    val top: DiaryHit?,
    val runnerUp: DiaryHit?,
    /** `top - runnerUp`, or [NO_COMPETITOR] when fewer than two hits were found. */
    val margin: Float,
    /** True when [top] may be acted on without human disambiguation. */
    val actionableAlone: Boolean,
    /** Set when the search could not be run at all (foreign embedding generation). */
    val suppression: SearchSuppression? = null,
) {
    val suppressed: Boolean get() = suppression != null

    companion object {
        const val NO_COMPETITOR = 0f
    }
}

enum class SearchSuppression {
    /** Invariant I1: the query embedding is from another model generation. */
    EMB_MODEL_MISMATCH,
}

object DiarySearch {

    /**
     * @param topK how many candidates to pull before deciding. Always at least two are
     *   requested: with one hit the margin is undefined and the search would report a lone
     *   0.81 as decisive, which is the exact false-RED this margin exists to prevent.
     */
    fun search(
        diary: Diary,
        embedding: Embedding,
        reg: ThresholdRegistry,
        topK: Int = reg[ThresholdName.DIARY_TOP_K].toInt(),
    ): DiarySearchResult {
        if (!EmbModel.sameGeneration(embedding.modelTag, diary.embeddingGeneration())) {
            return DiarySearchResult(
                hits = emptyList(),
                top = null,
                runnerUp = null,
                margin = DiarySearchResult.NO_COMPETITOR,
                actionableAlone = false,
                suppression = SearchSuppression.EMB_MODEL_MISMATCH,
            )
        }
        val hits = diary.search(embedding, maxOf(topK, MIN_TOP_K_FOR_MARGIN))
        return withMargin(hits, reg)
    }

    /**
     * Watchlist band (A_WL_01): only hits at or above [ThresholdName.T_WL], ranked with the
     * same margin discipline.
     *
     * A watchlist pack is small and specific, so the margin matters less here than in the main
     * diary — but the ranking rule is shared anyway so that a single code path produces every
     * similarity the UI shows, and therefore every similarity the audit log can be checked
     * against.
     */
    fun watchlist(
        diary: Diary,
        embedding: Embedding,
        reg: ThresholdRegistry,
        topK: Int = reg[ThresholdName.DIARY_TOP_K].toInt(),
    ): DiarySearchResult {
        val searched = search(diary, embedding, reg, topK)
        if (searched.suppressed) return searched
        val floor = reg[ThresholdName.T_WL].toFloat()
        return withMargin(searched.hits.filter { it.similarity >= floor }, reg)
    }

    private fun withMargin(hits: List<DiaryHit>, reg: ThresholdRegistry): DiarySearchResult {
        val top = hits.getOrNull(0)
        val runnerUp = hits.getOrNull(1)
        if (top == null) {
            return DiarySearchResult(emptyList(), null, null, DiarySearchResult.NO_COMPETITOR, false)
        }
        val margin = if (runnerUp == null) DiarySearchResult.NO_COMPETITOR else top.similarity - runnerUp.similarity
        val minMargin = reg[ThresholdName.DELTA_MARGIN].toFloat()
        return DiarySearchResult(
            hits = hits,
            top = top,
            runnerUp = runnerUp,
            margin = margin,
            actionableAlone = runnerUp == null || margin >= minMargin,
        )
    }

    private const val MIN_TOP_K_FOR_MARGIN = 2
}
