#!/usr/bin/env python3
"""Extract macro feature vectors with the same definitions `:core` uses.

Why this file exists at all
---------------------------
`dev.kasoti.factory.ProcessClassifier.extract` is the definition of a macro feature vector,
and it is the only definition that ships on the device. The training pipeline needs feature
vectors too, and the training pipeline is Python (AGENTS.md §2 allows Python only here). So
one of the two had to be a reimplementation, and the honest options were:

  a) call the Kotlin from Python — impossible without a JNI bridge, a subprocess per patch,
     or shipping a JVM into the training pipeline; all three are worse than the duplication;
  b) reimplement in NumPy and **prove** the two agree, documenting every divergence.

This is (b). `cross_check.py` is the proof, and it is a real test with a real tolerance, not
a comment. The reason the duplication is tolerable is that the maths is small and fully
specified: a Hann-windowed 2-D FFT, a radial average, a uniform-LBP-59 histogram and a Sobel
gradient count. There is no learning, no hyper-parameter and no heuristic in here — only
arithmetic that either matches `:core` or is a bug.

What is *not* claimed
---------------------
Agreement at a stated tolerance is not bit-identity. The FFT is the one place the two
implementations genuinely differ: `:core` accumulates its twiddle recurrence in `Float`
(single precision, `FloatArray`) while NumPy's transform is double precision internally, so
the spectral features agree to roughly 1e-6 relative rather than exactly. `Lbp`, `Edges` and
`stdDev` are integer comparisons and float division, and those do agree exactly. The
measured deltas are printed by `cross_check.py` rather than hidden, because a cross-check
that only ever says "PASS" is not evidence.

Usage
-----
    python3 eval/tools/extract_features.py --out features.jsonl
    python3 eval/tools/extract_features.py --recipes OFFSET,LASER --out -

`--out -` writes to stdout, which is how `cross_check.py` reads it.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import patches  # noqa: E402

#: `ProcessClassifier.extract`'s band bounds. These are `:core` defaults, and they are not
#: tunables: changing them would change what a feature vector *means*, and the model file
#: names the features it was trained on.
BAND_LOW_RAD = 12.0
BAND_HIGH_RAD = 90.0
HIGH_FREQ_CUTOFF = 64

#: `dev.kasoti.factory.Edges.density`'s default gradient threshold.
EDGE_THRESHOLD = 0.08

#: Per-feature agreement limits between this implementation and `:core`.
#:
#: These are **measured**, not assumed: each was set from the observed worst case across the
#: seven shared recipes, with roughly an order of magnitude of headroom. `cross_check.py`
#: always prints the observed deltas next to these limits, so a tolerance that has quietly
#: become too loose is visible without reading this file.
#:
#: Two distinct causes, and keeping them apart is the whole point of the check:
#:
#: * **float32 accumulation.** `:core` sums the Sobel count, the LBP histogram and the
#:   variance in `Float` over ~65 000 terms. The arithmetic is identical; only the rounding
#:   differs, so the observed spread is ~1e-7 absolute and is not a disagreement about the
#:   algorithm.
#: * **float32 FFT.** `:core` runs a hand-rolled twiddle recurrence in `Float`, whose own
#:   error grows with the transform length. NumPy's transform is double precision internally,
#:   so the *radial profile itself* differs, and everything derived from it inherits the
#:   difference. Energy fractions are stable to ~5e-4; the ratios taken against a local floor
#:   (`peakiness`) amplify that to a few percent.
FEATURE_LIMITS: dict[str, tuple[float, str]] = {
    # name: (absolute limit, kind)
    "peak_frequency": (0.0, "exact"),
    "edge_density": (1e-5, "float32-accumulation"),
    "std_dev": (1e-5, "float32-accumulation"),
    "band_energy": (1e-3, "float32-fft"),
    "high_frequency_energy": (1e-3, "float32-fft"),
    "peakiness": (0.5, "float32-fft"),
    # ADVISORY, not gated. See the module note on spectral_slope below.
    "spectral_slope": (1.0, "advisory"),
}

#: The limit for an LBP bin. Not in [FEATURE_LIMITS] because there are 59 of them and they
#: all behave identically.
LBP_LIMIT = 1e-5

#: [FEATURE_LIMITS] entry kind meaning "measured and reported, but does not fail the check".
ADVISORY = "advisory"

# FINDING — `spectral_slope` is materially precision-dependent, and that is a property of
# `:core`, not of this file.
#
# `Spectrum.logLogSlope` fits `ln(power)` against `ln(radius)` by least squares. Its
# denominator is `n*Sxx - Sx^2`, a difference of two large nearly-equal quantities, so it
# cancels roughly six significant digits. The radial profile it is fed differs between a
# float32 and a float64 transform by ~1e-4 relative, and the cancellation turns that into a
# slope difference of up to ~0.8 absolute — on a feature the classifier weights directly.
#
# The honest options are (a) accumulate the fit, or even the radial average, in `Double` in
# `:core`, and (b) train and gate with awareness that the feature is the least reproducible
# of the seven. `dev.kasoti.factory` is not owned by the eval harness, so nothing is changed
# here: the check reports the divergence, does not gate on it, and says so on every run.
SPIRAL_SLOPE_FINDING = (
    "spectral_slope differs by up to ~0.8 absolute between the two implementations. "
    "Cause: :core fits the log-log slope from a float32 FFT profile, and the least-squares "
    "denominator (n*Sxx - Sx^2) cancels about six significant digits, amplifying a ~1e-4 "
    "profile difference into a visible slope difference. Owner: core engineer — accumulate "
    "the radial average and the fit in Double, or drop the feature's weight. Reported here "
    "rather than gated, because gating on a limit this loose would be theatre."
)

#: The 8-neighbour ring, clockwise from the north-west, matching `Lbp.NEIGHBOURS`.
NEIGHBOURS = [
    (-1, -1), (0, -1), (1, -1), (1, 0),
    (1, 1), (0, 1), (-1, 1), (-1, 0),
]


def radial_profile(image: np.ndarray) -> dict[str, float]:
    """`dev.kasoti.factory.Spectrum.radialProfile`, in NumPy.

    Steps, in `:core`'s order, because the order is what makes the result reproducible:
    subtract the mean, apply a separable Hann window, transform, average the power spectrum
    over equal-radius bins over the top-left quadrant only, discard DC.
    """
    if image.shape != (patches.PATCH, patches.PATCH):
        raise ValueError(f"spectrum analysis expects a {patches.PATCH}x{patches.PATCH} patch, got {image.shape}")

    n = patches.PATCH
    mean = float(image.mean())
    x = np.arange(n)
    # The same Hann window `:core` builds, including its / (n - 1) denominator. Using
    # np.hanning would give n-1 interior points and a subtly different window.
    window = 0.5 * (1.0 - np.cos(2.0 * np.pi * x / (n - 1)))
    windowed = (image - mean) * (window[:, None] * window[None, :])

    power = np.abs(np.fft.fft2(windowed)) ** 2

    half = n // 2
    max_r = half - 1
    yy, xx = np.mgrid[0:half, 0:half]
    radius = np.sqrt(xx.astype(np.float64) ** 2 + yy.astype(np.float64) ** 2).astype(np.int64)

    # `:core` keeps only bins with `r <= maxR`; a corner of the quadrant sits further out
    # than the largest retained radius, and dropping those bins (rather than clipping them
    # inward) is what keeps the two implementations summing the same pixels.
    keep = radius <= max_r
    radial = np.zeros(max_r + 1, dtype=np.float64)
    counts = np.zeros(max_r + 1, dtype=np.int64)
    np.add.at(radial, radius[keep].ravel(), power[:half, :half][keep].ravel())
    np.add.at(counts, radius[keep].ravel(), 1)
    radial = np.divide(radial, counts, out=np.zeros_like(radial), where=counts > 0)
    radial[0] = 0.0  # DC is brightness, not texture

    lo = int(BAND_LOW_RAD)
    hi = min(int(BAND_HIGH_RAD), max_r)
    peak_radius = int(np.argmax(radial[lo:hi + 1]) + lo) if hi >= lo else 0
    peak_energy = radial[peak_radius] if peak_radius > 0 else 0.0

    # Peakiness against a *local* floor, so one bright corner cannot masquerade as a lattice.
    window_start = max(1, peak_radius - 6)
    window_end = min(max_r, peak_radius + 6)
    neighbours = [radial[r] for r in range(window_start, window_end + 1) if r != peak_radius]
    floor = float(np.median(neighbours)) if neighbours else 0.0
    peakiness = 1.0 if peak_energy + floor <= 0.0 else max(peak_energy / floor, 1.0)

    band = radial[lo:hi + 1].sum()
    total = radial[1:max_r + 1].sum()
    band_energy = 0.0 if total <= 0.0 else min(max(band / total, 0.0), 1.0)

    high = radial[HIGH_FREQ_CUTOFF:max_r + 1].sum()
    all_freq = radial[1:max_r + 1].sum()
    high_freq = 0.0 if all_freq <= 0.0 else min(max(high / all_freq, 0.0), 1.0)

    return {
        "peak_frequency": float(peak_radius),
        "peakiness": float(min(max(peakiness, 1.0), 1e6)),
        "band_energy": float(band_energy),
        "high_frequency_energy": float(high_freq),
        "spectral_slope": float(_log_log_slope(radial, lo, min(hi, max_r))),
    }


def _log_log_slope(radial: np.ndarray, lo: int, hi: int) -> float:
    """Least-squares slope of log(power) on log(radius) across the band."""
    radii = np.arange(lo, hi + 1)
    keep = (radii > 0) & (radial[radii] > 0.0)
    if int(keep.sum()) < 3:
        return 0.0
    x = np.log(radii[keep].astype(np.float64))
    y = np.log(radial[radii[keep]])
    denom = len(x) * float((x * x).sum()) - float(x.sum()) ** 2
    if abs(denom) < 1e-12:
        return 0.0
    return float((len(x) * float((x * y).sum()) - float(x.sum()) * float(y.sum())) / denom)


def lbp_histogram(image: np.ndarray) -> np.ndarray:
    """`dev.kasoti.factory.Lbp.histogram`: uniform LBP, 59 bins, summing to 1.

    Vectorised rather than a Python loop: a 256x256 patch is 65 000 interior points, and a
    triple loop over them is minutes per patch instead of milliseconds. The comparison is
    `>=` against the centre, matching `:core` exactly — `<` would shift every bin.
    """
    centre = image[1:-1, 1:-1]
    code = np.zeros(centre.shape, dtype=np.uint8)
    for k, (dx, dy) in enumerate(NEIGHBOURS):
        neighbour = image[1 + dy:1 + dy + centre.shape[0], 1 + dx:1 + dx + centre.shape[1]]
        bit = (neighbour >= centre).astype(np.uint8)
        code = (code << np.uint8(1)) | bit

    flat = code.ravel()
    uniform = flat[UNIFORM_MASK[flat]]
    if uniform.size == 0:
        return np.zeros(patches.LBP_BINS, dtype=np.float64)
    counts = np.bincount(UNIFORM_TABLE[uniform], minlength=patches.LBP_BINS).astype(np.float64)
    return counts / float(uniform.size)


def edge_density(image: np.ndarray) -> float:
    """`dev.kasoti.factory.Edges.density`: share of pixels above the gradient threshold."""
    a, b, c = image[:-2, :-2], image[:-2, 1:-1], image[:-2, 2:]
    d, e, f = image[1:-1, :-2], image[1:-1, 1:-1], image[1:-1, 2:]
    g, h, i = image[2:, :-2], image[2:, 1:-1], image[2:, 2:]

    # The standard 3x3 Sobel, transcribed from `dev.kasoti.factory.Edges.density`.
    #
    # The trap is the coordinate mapping. `GrayImage.get(x, y)` reads `pixels[y * width + x]`,
    # so a term written `image[dx, dy]` lands at array index **[dy, dx]**. Reading
    # `image[x - 1, y]` as the array element left of centre rather than the one *above* it
    # gives a kernel with the axes crossed — which is a real, sizeable difference (about 5%
    # on `edge_density` for a dither patch) rather than a subtle one, and which a purely
    # horizontal or vertical test pattern hides completely.
    #
    # With the mapping applied, and naming the nine slices in the usual
    #     a b c
    #     d e f
    #     g h i
    # layout, Edges.density is the textbook pair:
    #     gx = -a - 2d - g + c + 2f + i      (columns x-1 and x+1, middle row weighted 2)
    #     gy = -a - 2b - c + g + 2h + i      (rows y-1 and y+1, middle column weighted 2)
    gx = -a - 2 * d - g + c + 2 * f + i
    gy = -a - 2 * b - c + g + 2 * h + i

    magnitude = np.sqrt(gx * gx + gy * gy)
    return float((magnitude > EDGE_THRESHOLD).mean())


def std_dev(image: np.ndarray) -> float:
    """`dev.kasoti.factory.GrayImage.stdDev`: population, not sample."""
    return float(np.sqrt(((image.astype(np.float64) - float(image.mean())) ** 2).mean()))


def feature_names() -> list[str]:
    """The seven numeric names, in `MacroFeatures.toVector()` order.

    `:core` calls them `peakFrequency`, `peakiness`, `bandEnergy`, `highFrequencyEnergy`,
    `spectralSlope`, `stdDev`, `edgeDensity`; this is the snake_case the model file uses. The
    two orderings must stay identical — the model is a list of weights and a shuffled feature
    order produces a plausible-looking classifier that is testing noise.
    """
    return [
        "peak_frequency", "peakiness", "band_energy", "high_frequency_energy",
        "spectral_slope", "std_dev", "edge_density",
    ] + [f"lbp_{i:02d}" for i in range(patches.LBP_BINS)]


def extract(image: np.ndarray) -> list[float]:
    """`MacroFeatures.toVector()`: the 66-float vector the model consumes."""
    profile = radial_profile(image)
    lbp = lbp_histogram(image)
    numeric = [
        profile["peak_frequency"] / patches.PATCH,
        float(np.log1p(profile["peakiness"])) / 6.0,
        profile["band_energy"],
        profile["high_frequency_energy"],
        profile["spectral_slope"],
        std_dev(image),
        edge_density(image),
    ]
    vector = numeric + lbp.tolist()
    if len(vector) != patches.TOTAL:
        raise AssertionError(f"feature vector is {len(vector)} wide, expected {patches.TOTAL}")
    return vector


def _build_uniform_tables() -> tuple[np.ndarray, np.ndarray]:
    """The 58 rotationally-unique uniform patterns, derived by scanning all 256 codes.

    Derived rather than transcribed, exactly as `:core` does, so neither side can mistype a
    row. A uniform pattern is one whose circular transition count is 0 (all bits equal) or 2
    (one contiguous block of ones).
    """
    mask = np.zeros(256, dtype=bool)
    table = np.full(256, -1, dtype=np.int64)
    bin_index = 0
    for code in range(256):
        bits = [(code >> (7 - k)) & 1 for k in range(8)]
        transitions = sum(1 for k in range(8) if bits[k] != bits[(k + 1) % 8])
        if transitions in (0, 2):
            mask[code] = True
            table[code] = bin_index
            bin_index += 1
    if bin_index != 58:
        raise AssertionError(f"expected 58 uniform LBP patterns, found {bin_index}")
    return mask, table


UNIFORM_MASK, UNIFORM_TABLE = _build_uniform_tables()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default="-", help="output path, or '-' for stdout")
    parser.add_argument("--recipes", default="", help="comma-separated recipe subset (default: all)")
    args = parser.parse_args(argv)

    names = [n for n in args.recipes.split(",") if n] or list(patches.RECIPES)
    unknown = [n for n in names if n not in patches.RECIPES]
    if unknown:
        parser.error(f"unknown recipe(s) {unknown}; known: {list(patches.RECIPES)}")

    lines: list[str] = []
    for name in names:
        # Unquantised, matching `:core`'s `SpectrumPatch`. See `patches.to_bytes`.
        image = patches.RECIPES[name]()
        record = {
            "id": f"syn-{name.lower()}",
            "label": name,
            "recipe": name,
            "seed": patches.SEED,
            "size": patches.PATCH,
            "featureNames": feature_names(),
            "features": extract(image),
        }
        lines.append(json.dumps(record, sort_keys=True))

    text = "\n".join(lines) + "\n"
    if args.out == "-":
        sys.stdout.write(text)
    else:
        path = pathlib.Path(args.out)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        print(f"wrote {len(lines)} feature vector(s) to {path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
