package dev.kasoti.platform.collector

import dev.kasoti.platform.imaging.ImageIoImaging
import dev.kasoti.platform.imaging.NormRect
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The D-MACRO patch collector (DATA.md §3) — the unblock for the macro corpus.
 *
 * Why this is a plain class and not a screen: the corpus is collected by a handful of
 * people, offline, on their own machines, and the thing standing between the team and
 * several hundred labelled patches is a reliable way to *put a patch in the right directory
 * with the right label*. Every one of those is a clerical decision, and every clerical
 * decision is best made once, in code, where it can be tested.
 *
 * Guarantees this class makes:
 *  - the file lands at `eval/data/macro/<process>/<light>/<clip|noclip>/<sourceid>_<n>.png`
 *    exactly as DATA.md §3 specifies, with the label enums rejecting typos before any
 *    directory is created;
 *  - repeated runs never overwrite an earlier sample — the counter resumes from what is
 *    already on disk, so two operators collecting into the same tree cannot collide;
 *  - the manifest gains exactly one row per sample, and the row is the six columns
 *    DATA.md §3 names, in that order.
 */
class MacroCollector(
    private val corpusRoot: Path,
    private val imaging: ImageIoImaging = ImageIoImaging(),
) {

    /**
     * Capture one labelled 256×256 gray patch and append its manifest row.
     *
     * @param indexOverride force a specific `n`; `null` picks the next free index. Exists for
     *   the test that asserts the layout without racing the filesystem.
     */
    @Throws(IOException::class)
    fun capture(request: MacroCaptureRequest, indexOverride: Int? = null): MacroSample {
        validate(request)

        val bucket = corpusRoot.resolve(request.process.label)
            .resolve(request.light.label)
            .resolve(if (request.clip) CLIP_DIR else NOCLIP_DIR)
        Files.createDirectories(bucket)

        val index = indexOverride ?: nextFreeIndex(bucket, request.sourceId)
        val fileName = "${request.sourceId}_$index.png"
        val target = bucket.resolve(fileName)
        if (Files.exists(target)) {
            throw IOException("refusing to overwrite an existing sample: $target")
        }

        val patch = extractPatch(request)
        imaging.writeGrayPng(patch, target)

        val row = manifestRow(request, fileName)
        val sample = MacroSample(
            relativePath = corpusRoot.relativize(target).toString().replace('\\', '/'),
            absolutePath = target,
            fileName = fileName,
            manifestFile = corpusRoot.resolve(MANIFEST_NAME),
            manifestRow = row,
            patchWidth = patch.width,
            patchHeight = patch.height,
            grayMean = patch.mean,
            grayStdDev = patch.stdDev,
        )
        appendManifestRow(row)
        return sample
    }

    /**
     * Centre-crop, resize and luma-convert the marked region.
     *
     * Normalisation is deliberately *only* the 0..255 → 0..1 step that `GrayImage.fromBytes`
     * performs, plus the bilinear resize. Contrast stretch, white balance and gamma would
     * all "help" the classifier and all destroy the thing it is trying to measure: the
     * halftone lattice and the toner speckle are the *evidence*. DATA.md §5's white-balance
     * and scale normalisation belong to the calibration card routine, which is a different
     * stage with its own provenance record (`calib-id`).
     */
    private fun extractPatch(request: MacroCaptureRequest): dev.kasoti.factory.GrayImage {
        val rgb = imaging.readRgb(request.sourceImage)
        return imaging.centreCropGray(rgb, request.region, PATCH_SIZE)
    }

    private fun validate(request: MacroCaptureRequest) {
        if (request.sourceId.isBlank()) throw IllegalArgumentException("sourceId must not be blank")
        // The id becomes a path segment and a manifest column; DATA.md §1 forbids it from
        // carrying PII, and the characters below are the ones that let it break the layout
        // or smuggle a separator into the path.
        val illegal = request.sourceId.filter { it !in SOURCE_ID_ALLOWED }
        require(illegal.isEmpty()) {
            "sourceId '$request.sourceId' contains ${illegal.toSet()} which would break the path layout"
        }
        require(request.sourceId.length <= MAX_SOURCE_ID_LENGTH) {
            "sourceId must be at most $MAX_SOURCE_ID_LENGTH characters"
        }
        if (request.calibId.isBlank()) {
            // DATA.md §5 makes calibration a precondition for the macro eval. A blank id is
            // how an uncalibrated sample gets quietly into the corpus and skews every
            // threshold tuned against it.
            throw IllegalArgumentException("calibId is required (DATA.md §5: calibrate before macro eval)")
        }
    }

    /**
     * The next unused index for this source in this bucket.
     *
     * Scans the directory rather than keeping a counter in memory: a collector that is run
     * from a fresh process — which is how it is actually used, one capture at a time — has
     * no other way to know what is already there, and an in-memory counter silently
     * overwrites sample 1 on every invocation after a restart.
     */
    private fun nextFreeIndex(bucket: Path, sourceId: String): Int {
        val prefix = "${sourceId}_"
        val existing = Files.newDirectoryStream(bucket).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
        val highest = existing.asSequence()
            .mapNotNull { name ->
                if (!name.startsWith(prefix) || !name.endsWith(".png")) return@mapNotNull null
                name.removePrefix(prefix).removeSuffix(".png").toIntOrNull()
            }
            .maxOrNull() ?: 0
        return highest + 1
    }

    private fun manifestRow(request: MacroCaptureRequest, fileName: String): String = listOf(
        request.sourceId,
        request.process.label,
        request.light.label,
        request.clip.toString(),
        request.deviceId,
        request.calibId,
    ).joinToString(",", postfix = ",$fileName") {
        it.ifBlank { "" }
    }

    private fun appendManifestRow(row: String) {
        val manifest = corpusRoot.resolve(MANIFEST_NAME)
        val needsHeader = !Files.exists(manifest) || Files.size(manifest) == 0L
        val text = buildString {
            if (needsHeader) append(HEADER).append('\n')
            append(row).append('\n')
        }
        Files.writeString(
            manifest,
            text,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
    }

    companion object {
        /** DATA.md §3: 256×256 patches. */
        const val PATCH_SIZE = 256

        const val CLIP_DIR = "clip"
        const val NOCLIP_DIR = "noclip"

        /** DATA.md §3: the manifest sits at the corpus root, one row per sample. */
        const val MANIFEST_NAME = "manifest.csv"

        /**
         * `source-id, process-label, light, clip, device, calib-id` followed by the file
         * name, so a row is self-contained: the corpus can be rebuilt or audited without
         * having to re-derive which file a row refers to.
         */
        const val HEADER = "source-id,process-label,light,clip,device,calib-id,file"

        private val SOURCE_ID_ALLOWED = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_')

        const val MAX_SOURCE_ID_LENGTH = 48

        /** The default corpus root, relative to the repository root. */
        fun defaultCorpusRoot(repositoryRoot: Path): Path = repositoryRoot.resolve("eval").resolve("data").resolve("macro")

        /** A full-frame region, for callers that have not yet worked out where the texture is. */
        fun fullFrame(): NormRect = NormRect(0f, 0f, 1f, 1f)
    }
}
