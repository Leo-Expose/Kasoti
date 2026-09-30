package dev.kasoti.platform.ml

import dev.kasoti.face.Embedding
import dev.kasoti.factory.GrayImage

/**
 * Face embedding, produced by the platform model and consumed by `:core` maths (DESIGN §5).
 *
 * `:core` owns cosine, quality gating and threshold selection. It does not own inference,
 * because inference is where a native library and a set of weight files appear, and DESIGN
 * D1 settles that argument once: same TFLite file on both platforms, same bytes, one hash.
 */
interface EmbeddingModel {

    /**
     * Identity of the loaded weights, e.g. `emb_v1@sha256:9f…`.
     *
     * Travels with every [Embedding] and every diary record. The comparability law
     * (DESIGN.md §3) says a diary written under one model generation may never be compared
     * against another — a `MobileFaceNet` vector and a `GhostFaceNet` vector are both
     * 128-dimensional unit vectors and cosine between them is meaningless noise.
     */
    val modelTag: String

    /**
     * @param faceCrop a 112×112 (or model-native) aligned, L2-safe grayscale crop of one face.
     * @return a 128-dimensional embedding carrying [modelTag].
     * @throws ModelLoadException if no model has been pinned and loaded, or if the crop is
     *   not a usable size. Never returns a fabricated vector.
     */
    fun embed(faceCrop: GrayImage): Embedding
}

/**
 * Reads a model file, checks it against the pin, and only then hands the bytes on
 * (invariant I12, RT-E8).
 *
 * @param pin the build-time expected digest, `name@sha256` (DESIGN.md §6 inventory).
 */
class PinnedEmbeddingModelSource(
    private val loader: ModelLoader,
    private val pin: PinnedModel,
) {
    /**
     * @return verified bytes plus the tag to stamp on every embedding they produce.
     * @throws ModelLoadException on a hash mismatch — the caller must not fall back to a
     *   different model, because "different model" means "decisions the audit cannot reproduce".
     */
    fun open(file: java.nio.file.Path): LoadedModel = loader.load(pin, file)
}
