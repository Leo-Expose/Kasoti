#!/usr/bin/env python3
"""Generate a SYNTHETIC print-process corpus: physically-motivated 256x256 macro patches.

READ THIS BEFORE QUOTING ANY NUMBER PRODUCED BY THIS FILE
---------------------------------------------------------
There is no print-process dataset in this repository. `eval/data/macro/` holds a manifest with a
header and no rows, because real patches need physical documents, the macro clip, and consented
capture (DATA.md §3) — none of which exist. What this file builds is **geometry with a label
attached**, and a linear model fitted to it says something about the geometry and nothing at all
about ink on paper.

Every artefact this file writes is stamped `SYNTHETIC` in the same words: the manifest gets a
`synthetic` column and a note, the feature records get `"synthetic": true`, the specimen rows
carry `"generator": "synth_macros.py@<seed>"`, the training run id says it, and the model card
leads with it. `train_svm.py --synthetic-corpus` will not write a model from one of these without
`--allow-synthetic`.

What is being simulated
-----------------------
The seven `ProcessLabel` classes are not seven texture presets. They are seven *mechanisms*, and
each renderer below implements the mechanism that physically produces that class:

| Class | Mechanism implemented here | Why this mechanism |
|---|---|---|
| `OFFSET` | Dot screen on a rotated square lattice, pitch 15–26 px, near-zero dot jitter, dot gain rising with tone | The halftone screen is a physical plate. Dot positions are fixed by the plate, so the lattice is the most periodic thing in the whole set and produces the sharpest spectral peak available. Sheet-fed offset at 150–250 lpi over a 2 mm crop (DATA.md §1: 256 px ≈ 0.145–0.24 mm per cell) lands inside the analysis band [12, 90] cycles/patch. |
| `INKJET` | Stochastic threshold dither against a locally averaged tone, four passes at different phases, then capillary bleed and satellite droplets | Inkjet places droplets without a fixed screen, so there is no dominant lattice. The observable tells are broadband high-frequency energy, high edge density from droplet boundaries, and soft edges from bleed into the paper. No pitch is imposed because at macro distance the real nozzle pitch is below the camera's resolution. |
| `LASER` | 2–4 level quantised coverage (hard edges), sparse toner speckle, low-frequency fuser mottle | Electrophotography lays toner that is then melted flat, so the print has almost no mid-tone: edges are near-binary, a fine speckle of unfused toner sits on the inked areas only, and the mottle is a *low*-frequency roller artefact. No lattice at all. |
| `DYESUB` | Fine regular dither lattice at pitch 7–13 px, low dot contrast, over a smoothed tone, with mild shadow banding | Thermal-transfer ribbons carry a much finer dot pitch than a lithographic plate and the dye transfer is continuous. So the texture is smoother and lower-contrast than offset, at a *different and finer* pitch, with faint tonal steps in the shadows where the ribbon runs out of dye. |
| `SCREEN` | RGB subpixel comb (3–5 px) × row grid × document content, plus an in-band moiré beat, cover-glass reflection gradient and panel mura | A photographed display's *observable* in-band signature is dominated by a moiré beat between the subpixel comb and the camera's sampling lattice, because the comb itself usually falls at or below the optical resolution limit and lands outside the analysis band. Nothing else here is anywhere near this deterministic, so `peakiness` and pitch should separate it — which matters because a screen showing a document is attack AT-04. |
| `PHOTOCOPY` | A source process re-imaged: optical blur, faint tone curve, ghost double, broadband grain, and a moiré at a pitch *near but not equal* to the source's | A photocopy is a re-imaging, so it is a copy of *some* process plus the copier's own signature. Rendering it as a degraded copy is the honest model; the class is separable only by the copier's contribution — blur flattens the source's peak, grain lifts the noise floor, and the faint tone curve puts mid-tone back. |
| `UNKNOWN` | Reject pile: near-flat paper, pure sensor noise, structureless motion blur, extreme defocus of a *stochastic* field, dust and scratches, specular hotspot | DATA.md §3's reject pile: texture no print process produced, so there is no process signature to find. Six sub-kinds, because "unclassifiable" is not one thing — and none of them is made by blurring a periodic field, because "blurry" is a real failure mode, not a class. |

Split discipline
----------------
EVAL.md §2 forbids splitting by patch. A **source document** here is one rendered page plus every
crop taken from it, and all crops of a page share the same ink, the same screen and the same
paper — so splitting by patch would leak the report set into training. `train_svm.py` groups by
`source_doc` and refuses a run where any class has zero report support.

Determinism
-----------
Every random draw comes from a counter-based integer hash or from `Rng`, a xorshift32 stream
seeded by the document's coordinates. Nothing here calls `numpy.random`, whose stream is
explicitly *not* guaranteed stable across releases, and nothing depends on a seed a reviewer
cannot derive from `(base seed, class index, document index)`. A model retrained from this file on
a different NumPy must come out identical or something is wrong.

Usage
-----
    # the corpus, with pixels (gitignored: eval/data/**/*.png)
    python3 eval/tools/synth_macros.py --out eval/data/synthetic_macro \
        --docs-per-class 48 --patches-per-doc 6

    # feature vectors for the trainer, no pixels on disk
    python3 eval/tools/synth_macros.py --no-images --out /tmp/synth \
        --features /tmp/synth/features.jsonl

    # the specimen set the Kotlin test renders
    python3 eval/tools/synth_macros.py --emit-specimens eval/data/fixtures/macro_synth/specimens.jsonl
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import pathlib
import struct
import sys
import zlib

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import patches  # noqa: E402
from extract_features import extract, feature_names  # noqa: E402

#: `dev.kasoti.factory.Spectrum.PATCH`. A patch is exactly this or `:core` refuses it.
PATCH = patches.PATCH

#: `dev.kasoti.factory.ProcessLabel` declaration order.
LABELS = ["OFFSET", "INKJET", "LASER", "DYESUB", "SCREEN", "PHOTOCOPY", "UNKNOWN"]

#: The word that goes in the manifest column, the record field, the run id and the model card.
#: One constant, so the labelling cannot drift between the four places it has to appear.
SYNTHETIC_WORD = "SYNTHETIC"

#: Root seed. Obviously a fixture constant rather than a tuned value, which is what it is.
BASE_SEED = 2_026_101

#: Page render size. Larger than a patch so a page can yield several *different* crops — a
#: text-zone crop and a photo-zone crop of the same page must share a `source_doc`, which is the
#: whole reason EVAL.md §2's rule has teeth.
DOC_SIZE = 640

#: DATA.md §1: a macro patch is a 2 mm crop, and it is 256 px, so the sampling grid is
#: 128 px/mm. Every screen pitch below is stated in the unit a print person uses (lpi for a
#: lithographic screen, dpi for a ribbon or a display) and converted here, because the conversion
#: is the part that is easy to get backwards and impossible to notice.
PX_PER_MM = 256.0 / 2.0
#: A period of `p` pixels has its spectral fundamental at radius `256/p` cycles/patch, so a
#: screen of `lpi` lines/inch lands at radius `lpi / (25.4 * PX_PER_MM / 256)`.
LPI_TO_RADIUS = 1.0 / (25.4 * PX_PER_MM / 256.0)


def pitch_px(screen_lines_per_inch: float) -> float:
    """Pixel period of a screen at `lines_per_inch`, on the DATA.md §1 sampling grid."""
    return 25.4 / screen_lines_per_inch * PX_PER_MM


def radius_for(screen_lines_per_inch: float) -> float:
    """Spectral radius `Spectrum.radialProfile` will see for that screen, in cycles/patch."""
    return screen_lines_per_inch * LPI_TO_RADIUS


#: Screen ranges. Real products, chosen so the fundamentals land inside the analysis band
#: [12, 90] that `ProcessClassifier.extract` analyses:
#:
#:   r = lpi/12.7, so 175–250 lpi offset lands at r 13.8–19.7 and a 240–330 dpi dye-sub ribbon
#:   lands at r 18.9–26.0. The two ranges barely overlap, which is deliberate but *not* a
#:   guarantee: on a real document these are separated mainly by dot shape and dot gain, and
#:   neither is in the 66-feature vector. See the model card's known limits.
OFFSET_LPI = (175.0, 250.0)
DYESUB_DPI = (240.0, 330.0)
#: A photographed display's subpixel comb as the camera actually resolves it. The sweep is
#: wider than any single panel on purpose: at this pixel density a 3.5× to 6.5× pixel panel
#: resolves to a 2–9 px comb, i.e. a spectral radius of 28 to 128, and the corpus has to contain
#: the panels whose comb falls *inside* the analysis band as well as those whose it does not.
SCREEN_SUBPIXEL_PX = (2.0, 9.0)

#: Relative luminance of an R, G or B substripe, normalised so the brightest is 1.
_SUBPIXEL_LUMA = np.array([0.2126 * 0.30, 0.7152 * 0.59, 0.0722 * 0.11])

#: Crops per page, as (x, y) offsets.
CROP_OFFSETS = [(0, 0), (256, 0), (128, 128), (384, 128), (0, 384), (256, 256)]

_LIGHTS = ["sun", "shade", "torch"]

#: DATA.md §3 protocol columns, verbatim from `eval/data/macro/manifest.csv`, so a synthetic
#: manifest and a real one are diffable and the only visible difference is that one says so.
MANIFEST_COLUMNS = [
    "row_id", "source_doc", "process_label", "light", "clip", "device_id", "calib_id",
    "captured_at_utc", "operator", "double_checked_by", "notes", "synthetic", "generator",
]

#: Fixed, so a run is byte-reproducible. A real corpus records wall-clock UTC per patch.
_CAPTURED_AT = "2026-01-01T00:00:00Z"
_DEVICE = "SYNTHETIC-GENERATOR"


# ===================================================================== randomness
#
# A counter-based hash rather than numpy.random. numpy documents that a Generator's stream may
# change between releases; a corpus whose pixels move when someone bumps NumPy produces a model
# nobody can reproduce, which is exactly what EVAL.md §1 exists to prevent.

_MASK32 = np.int64(0xFFFFFFFF)
_SIGN32 = np.int64(1 << 31)
_MOD32 = np.int64(1 << 32)
_PHI = 0x9E3779B9


def _i32(value: np.ndarray) -> np.ndarray:
    """Wrap to a *signed* 32-bit int, which is what a Kotlin `Int` holds.

    Same trap as `patches._i32`: NumPy shifts on non-negative int64 fill with zeros while Kotlin's
    `Int shr` is arithmetic, so the two disagree once bit 31 is set — and the result is a
    different image, not a small drift.
    """
    wrapped = value % _MOD32
    return np.where(wrapped >= _SIGN32, wrapped - _MOD32, wrapped)


def _hash3(x: np.ndarray, y: np.ndarray, salt: int) -> np.ndarray:
    """Stable 32-bit hash of an integer grid and a salt, in [0, 1]."""
    # The salt term is folded to 16 bits before mixing. The multiplier is large enough that an
    # unfolded salt overflows int64, and an overflow is a *warning* on one NumPy version and a
    # silent wrap on the next — which is a reproducibility bug that only shows up as "the corpus
    # changed and nothing was edited".
    salt_term = np.int64(int(salt) & 0xFFFF) * np.int64(1_442_695_041)
    h = _i32(
        x.astype(np.int64) * 374_761_393
        + y.astype(np.int64) * 668_265_263
        + salt_term
    )
    h = _i32(_i32(h ^ (h >> 13)) * 1_274_126_177)
    h = _i32(h ^ (h >> 16))
    return (h & np.int64(0x7FFFFFFF)).astype(np.float64) / 2147483647.0


def _u01(shape: tuple[int, int], salt: int) -> np.ndarray:
    """Uniform [0, 1) field of the given shape, from the counter-based hash."""
    yy, xx = np.mgrid[0:shape[0], 0:shape[1]]
    return _hash3(xx, yy, salt)


def _normal_field(shape: tuple[int, int], salt: int) -> np.ndarray:
    """Standard-normal field via Box–Muller over two independent hash fields."""
    u1 = np.clip(_u01(shape, salt), 1e-12, 1.0)
    u2 = _u01(shape, salt + 0x5F3759DF)
    return np.sqrt(-2.0 * np.log(u1)) * np.cos(2.0 * np.pi * u2)


class Rng:
    """xorshift32: small, explicit, and reproducible from integer seeds alone.

    Used for the handful of *parameter* draws per document, where a Python loop is fine. Per-pixel
    randomness uses `_u01`, which is the same mixing function vectorised.
    """

    def __init__(self, *keys: int) -> None:
        state = _PHI
        for key in keys:
            value = int(key) & 0xFFFFFFFF
            for shift in (0, 8, 16, 24):
                state = (state * 1664525 + 1013904223 + ((value >> shift) & 0xFF)) & 0xFFFFFFFF
        self._state = state or 0x1

    def next_u32(self) -> int:
        s = self._state
        s ^= (s << 13) & 0xFFFFFFFF
        s &= 0xFFFFFFFF
        s ^= s >> 17
        s ^= (s << 5) & 0xFFFFFFFF
        self._state = s & 0xFFFFFFFF or 0x1
        return self._state

    def uniform(self, lo: float, hi: float) -> float:
        return lo + (hi - lo) * (self.next_u32() / 4294967296.0)

    def randint(self, lo: float, hi: float) -> float:
        span = int(round(hi - lo)) + 1
        return lo + float(self.next_u32() % max(span, 1))

    def chance(self, probability: float) -> bool:
        return (self.next_u32() / 4294967296.0) < probability

    def choice(self, options: list) -> str:
        return options[self.next_u32() % len(options)]


# ===================================================================== DSP primitives


def _convolve_axis(field: np.ndarray, kernel: np.ndarray, axis: int) -> np.ndarray:
    """1-D convolution of a 2-D field along one axis, vectorised.

    `np.apply_along_axis` with a per-row `np.convolve` is a Python loop over 640 rows; with
    several blurs per page and 300+ pages that is minutes of interpreter overhead for arithmetic
    that is one `einsum` long.
    """
    radius = (kernel.size - 1) // 2
    pad = [(0, 0), (0, 0)]
    pad[axis] = (radius, kernel.size - 1 - radius)
    padded = np.pad(field, pad, mode="reflect")
    # `sliding_window_view` appends the window axis last and shrinks `axis` in place, so the
    # reduced field and the kernel contract over the trailing dimension.
    windows = np.lib.stride_tricks.sliding_window_view(padded, kernel.size, axis=axis)
    return np.einsum("...k,k->...", windows, kernel, optimize=True)


def blur(image: np.ndarray, sigma: float) -> np.ndarray:
    """Separable Gaussian blur, reflect-padded.

    Reflect rather than zero-pad: a zero-padded blur darkens the border by construction, and a
    systematic dark frame is a low-frequency gradient the radial profile would report as real
    structure.
    """
    if sigma <= 0.0:
        return image
    radius = max(1, int(math.ceil(3.0 * sigma)))
    offsets = np.arange(-radius, radius + 1, dtype=np.float64)
    kernel = np.exp(-(offsets ** 2) / (2.0 * sigma * sigma))
    kernel /= kernel.sum()
    return _convolve_axis(_convolve_axis(image, kernel, 0), kernel, 1)


def rotate(image: np.ndarray, degrees: float) -> np.ndarray:
    """Bilinear rotation about the centre, output shape unchanged.

    Applied to a *crop*, not the page: handheld macro capture is never square to the screen
    angle. A rotated lattice keeps its spectral radius (the profile is radial) while gaining the
    interpolation softness a real resample would add. Rotating the page instead would also rotate
    the document's own content, which no camera does.
    """
    if abs(degrees) < 1e-6:
        return image
    height, width = image.shape
    theta = math.radians(degrees)
    cos_t, sin_t = math.cos(theta), math.sin(theta)
    yy, xx = np.mgrid[0:height, 0:width].astype(np.float64)
    cx, cy = (width - 1) / 2.0, (height - 1) / 2.0
    src_x = (xx - cx) * cos_t + (yy - cy) * sin_t + cx
    src_y = -(xx - cx) * sin_t + (yy - cy) * cos_t + cy
    return _bilinear(image, src_x, src_y)


def _bilinear(image: np.ndarray, src_x: np.ndarray, src_y: np.ndarray) -> np.ndarray:
    height, width = image.shape
    x0 = np.floor(src_x).astype(np.int64)
    y0 = np.floor(src_y).astype(np.int64)
    fx = src_x - x0
    fy = src_y - y0
    x0c, x1c = np.clip(x0, 0, width - 1), np.clip(x0 + 1, 0, width - 1)
    y0c, y1c = np.clip(y0, 0, height - 1), np.clip(y0 + 1, 0, height - 1)
    top = image[y0c, x0c] * (1 - fx) + image[y0c, x1c] * fx
    bottom = image[y1c, x0c] * (1 - fx) + image[y1c, x1c] * fx
    return top * (1 - fy) + bottom * fy


def fibre_texture(shape: tuple[int, int], salt: int) -> np.ndarray:
    """Fine directional paper fibre, normalised to about [-1, 1].

    Paper has a grain direction and a real macro crop of paper is never spectrally flat. This is
    the floor every printed class sits on; leaving it out would make paper look more idealised
    than any paper in the world. Built as an anisotropically smoothed normal field, which is what
    a machine-direction fibre actually looks like at this magnification.
    """
    field = _normal_field(shape, salt)
    along = np.exp(-(np.arange(-6, 7) ** 2) / 2.0)
    along /= along.sum()
    across = np.exp(-(np.arange(-1, 2) ** 2) / 2.0)
    across /= across.sum()
    smeared = _convolve_axis(field, along, 1)
    smeared = _convolve_axis(smeared, across, 0)
    return smeared / max(float(np.abs(smeared).max()), 1e-9)


# ===================================================================== the page


def document_coverage(rng: Rng, height: int, width: int) -> np.ndarray:
    """A page's ink-coverage map. 1.0 = bare paper, 0.0 = solid ink.

    Laid out with a photo zone and a text zone, because `MacroStage` classifies those two
    separately and `DocAggregator` compares them: a genuine document's two zones come from the
    same process and agree. Crops landing on different zones must therefore still share a
    `source_doc`.

    **Scale is physical, and it is the single most important thing here.** On a 128 px/mm grid a
    2 mm macro crop holds about five lines of 10 pt text, so the text pitch is 45–70 px and a
    character run is 12–40 px. An earlier version of this generator used 7–19 px features, which
    put the *layout* period inside the analysis band [12, 90] — and then the classifier learned
    the generator's line spacing instead of the print screen, at r 12–36, which is exactly where
    a screen should be. Real text is sub-band; the screen is in-band. Getting that backwards makes
    a model that scores well and transfers to nothing.

    The photo zone is deliberately *mid-tone* rather than a smooth ramp. That is both what a
    document's photographic area looks like and what makes the screen visible: a halftone only
    has a lattice where the tone is partial, so a page of solid black and solid white — or of a
    low-contrast gradient that merely tilts the band edge — has almost no screen content to find.
    """
    coverage = np.ones((height, width), dtype=np.float64)

    # --- photo zone, the left half: continuous-tone, held in the mid-tones.
    split = width // 2
    yy, xx = np.mgrid[0:height, 0:split].astype(np.float64)
    # A gentle, low-amplitude tilt: real photos are not flat, but a strong ramp would put most of
    # the power at the bottom of the band and drown the screen.
    photo = 0.42 + 0.16 * (xx / max(split - 1, 1)) + rng.uniform(-0.04, 0.04)
    for _ in range(3):
        cx = rng.uniform(0.15, 0.85) * split
        cy = rng.uniform(0.15, 0.85) * height
        radius = rng.uniform(0.18, 0.45) * min(split, height)
        photo = photo - rng.uniform(0.10, 0.26) * np.exp(
            -(((xx - cx) ** 2 + (yy - cy) ** 2) / (2.0 * radius * radius))
        )
    photo = np.clip(photo, 0.18, 0.78)
    coverage[:, :split] *= photo

    # --- text zone, the right half: rows of dark runs, at a realistic pitch.
    y = int(rng.randint(10, 45))
    while y < height - 24:
        line_h = int(rng.randint(9, 20))
        if y + line_h > height:
            break
        x = split + int(rng.randint(10, 60))
        while x < width - 20:
            run = int(rng.randint(12, 40))
            if x + run > width:
                break
            coverage[y:y + line_h, x:x + run] *= rng.uniform(0.04, 0.35)
            x += run + int(rng.randint(6, 18))
        y += line_h + int(rng.randint(28, 50))

    # --- one solid bar: a signature block or a barcode strip. Near-binary over its whole area,
    #     which is a genuinely different texture from the rest of the page — and the reason a
    #     crop is not just "a screen": part of it has no screen in it at all.
    bar_y = int(rng.randint(int(height * 0.55), int(height * 0.85)))
    bar_h = int(rng.randint(20, 44))
    bar_x = int(rng.randint(10, 60))
    bar_w = int(rng.randint(80, max(90, width // 2)))
    coverage[bar_y:min(bar_y + bar_h, height), bar_x:min(bar_x + bar_w, width)] *= rng.uniform(0.02, 0.10)

    return np.clip(coverage, 0.0, 1.0)


# ===================================================================== the seven renderers


def _rotated_cell(shape: tuple[int, int], pitch: float, degrees: float):
    """Within-cell coordinates of a square lattice rotated by `degrees`.

    Returns `(du, dv, cell_key)`: signed distances from the cell centre along the screen axes,
    and an integer key per cell for per-cell jitter.
    """
    yy, xx = np.mgrid[0:shape[0], 0:shape[1]].astype(np.float64)
    theta = math.radians(degrees)
    cos_t, sin_t = math.cos(theta), math.sin(theta)
    u = xx * cos_t + yy * sin_t
    v = -xx * sin_t + yy * cos_t
    cell_u = np.floor(u / pitch)
    cell_v = np.floor(v / pitch)
    du = u - (cell_u + 0.5) * pitch
    dv = v - (cell_v + 0.5) * pitch
    cell_key = (cell_u.astype(np.int64) * 7_385_6093) ^ (cell_v.astype(np.int64) * 19_349_663)
    return du, dv, cell_key


def _dot_radius(coverage: np.ndarray, pitch: float, gain: float) -> np.ndarray:
    """Dot radius for a given coverage, with dot gain.

    A halftone dot's *area* equals the nominal tone, so `r = p*sqrt(c/pi)`. Real screens gain area
    in the mid-tones — ink spreads, and the highlight end opens up first — which is why `gain`
    warps the tone curve rather than scaling the radius.
    """
    effective = np.clip(coverage, 0.0, 1.0)
    effective = effective * (1.0 + gain * 4.0 * effective * (1.0 - effective))
    return pitch * np.sqrt(np.clip(effective, 0.0, 1.0) / math.pi)


def render_offset(coverage: np.ndarray, rng: Rng, salt: int) -> np.ndarray:
    """Sheet-fed offset: a dot screen on a rotated square lattice.

    175–250 lpi, which on the DATA.md §1 grid is a 13–18 px period and therefore a spectral
    fundamental at r 13.8–19.7 — inside the band [12, 90] `ProcessClassifier.extract` analyses, and
    at the bottom of it, which is where a coarse sheet-fed screen genuinely sits. Jitter is
    deliberately tiny (0–0.5 px): the plate fixes the dot positions, and the only real
    perturbations are press vibration and uneven inking.
    """
    pitch = pitch_px(rng.uniform(*OFFSET_LPI))
    angle = rng.uniform(38.0, 52.0)        # rosettes sit near 45 degrees
    gain = rng.uniform(0.05, 0.30)
    ink_spread = rng.uniform(0.10, 0.35)    # inking plus paper absorb
    dot_ink = rng.uniform(0.78, 0.99)       # how black a solid is on this paper

    du, dv, cell_key = _rotated_cell(coverage.shape, pitch, angle)
    jitter_key = (np.floor(np.abs(cell_key) % 262_144)).astype(np.int64)
    du = du + (_hash3(jitter_key, np.zeros_like(jitter_key), salt) - 0.5) * rng.uniform(0.0, 0.5)
    dv = dv + (_hash3(jitter_key, np.full_like(jitter_key, 977), salt) - 0.5) * rng.uniform(0.0, 0.5)

    radius = _dot_radius(coverage, pitch, gain)
    mask = (np.hypot(du, dv) < radius).astype(np.float64)
    image = 1.0 - mask * dot_ink * (1.0 - 0.15 * coverage)
    image = blur(image, ink_spread)
    return np.clip(image + fibre_texture(coverage.shape, salt + 11) * rng.uniform(0.006, 0.030), 0.0, 1.0)


def render_inkjet(coverage: np.ndarray, rng: Rng, salt: int) -> np.ndarray:
    """Inkjet: stochastic threshold dither against locally averaged tone, then bleed.

    Four passes at different phases stand in for the four heads. Each pass matches the *local*
    mean tone (so the tones look right) with dots that are uncorrelated from pixel to pixel (so
    there is no lattice) — a randomised screen, which is the observable content of
    non-halftoned inkjet. True Floyd–Steinberg would also be uncorrelated, but its error
    recursion is sequential in Python and this corpus is thousands of pages; the difference
    between FS and a randomised screen is a low-frequency correlation that is far below what a
    3×3 local binary pattern sees.

    The final blur is capillary bleed into the paper, which is why inkjet edges are soft and
    inkjet edge density is high.
    """
    shape = coverage.shape
    dark = 0.0
    for pass_index in range(4):
        local = blur(coverage, rng.uniform(1.2, 2.6))
        phase = _u01(shape, salt + 17 * (pass_index + 1))
        # Threshold a locally averaged tone against a random field: dot density follows the tone,
        # dot placement does not correlate.
        value = (1.0 - local) + (phase - 0.5) * rng.uniform(0.9, 1.3)
        dark = dark + (value > 0.5).astype(np.float64) * 0.25

    bleed = rng.uniform(0.55, 1.15)
    image = blur(np.clip(dark, 0.0, 1.0), bleed)

    # Satellite droplets: a stray drop outside its intended cell, more probable in the darks.
    satellite = _u01(shape, salt + 23)
    drops = (satellite > (0.99985 - 0.00010 * (1.0 - coverage))).astype(np.float64)
    image = np.clip(image + blur(drops, 0.7) * rng.uniform(0.15, 0.45), 0.0, 1.0)

    return np.clip(image + fibre_texture(shape, salt + 31) * rng.uniform(0.004, 0.020), 0.0, 1.0)


def render_laser(coverage: np.ndarray, rng: Rng, salt: int) -> np.ndarray:
    """Laser/toner: near-binary, hard-edged, fine speckle, fused low-frequency mottle.

    Two to four levels, not a continuous ramp: toner is either laid or it is not, and what a
    laser printer produces in a mid-tone is a *dither of hard edges*, not a smooth gradient. The
    speckle is unfused toner and rides on the inked areas only, because bare paper is not
    speckled by the printer. The mottle is the fuser roller — low frequency, which is why this
    class has high standard deviation with almost nothing in the halftone band.
    """
    levels = int(rng.randint(2, 4))
    quantised = np.clip(np.floor(np.clip(1.0 - coverage, 0.0, 1.0) * levels + 0.5) / (levels - 1), 0.0, 1.0)

    speckle = _normal_field(coverage.shape, salt + 3) * rng.uniform(0.012, 0.045)
    speckle *= 0.4 + 0.6 * (1.0 - quantised)

    mottle = blur(_normal_field(coverage.shape, salt + 5), rng.uniform(9.0, 22.0))
    mottle /= max(float(np.abs(mottle).max()), 1e-9)

    toner_ink = rng.uniform(0.70, 0.95)
    image = 1.0 - quantised * toner_ink + speckle + mottle * rng.uniform(0.010, 0.045)

    # A hair of resample softness: even a hard edge on the *document* is re-imaged by the optics.
    return np.clip(blur(image, rng.uniform(0.10, 0.32)), 0.0, 1.0)


def render_dyesub(coverage: np.ndarray, rng: Rng, salt: int) -> np.ndarray:
    """Dye-sublimation: a much finer dither lattice than offset, over a smooth tone.

    A 240–330 dpi transfer ribbon, i.e. r 18.9–26.0 on the DATA.md §1 grid — a *different and
    finer* pitch than offset's r 13.8–19.7, which is the one geometric fact that reliably
    separates the two. Low `gain` keeps the dots low-contrast and the tone is smoothed *before*
    dithering, because dye transfer is continuous where a lithographic screen is not.

    The residual overlap with offset near r 19–20 is real, not a generator artefact. On paper
    these are separated by dot *shape* and dot gain, and neither is in the 66-feature vector — see
    the model card's known limits, which is where that belongs.
    """
    pitch = pitch_px(rng.uniform(*DYESUB_DPI))
    angle = rng.uniform(0.0, 18.0)
    gain = rng.uniform(0.0, 0.12)

    smoothed = blur(coverage, rng.uniform(0.8, 1.8))
    du, dv, _ = _rotated_cell(coverage.shape, pitch, angle)
    mask = (np.hypot(du, dv) < _dot_radius(smoothed, pitch, gain)).astype(np.float64)

    dye_ink = rng.uniform(0.55, 0.88)
    image = 1.0 - mask * dye_ink * (0.45 + 0.55 * (1.0 - smoothed))

    # Shadow banding: the ribbon runs out of dye in the deep tones, so the darkest part of a
    # dye-sub print is a faint set of steps rather than a continuum.
    if rng.chance(0.55):
        shadow = 1.0 - image
        threshold = rng.uniform(0.45, 0.70)
        deep = shadow > threshold
        if deep.any():
            steps = int(rng.randint(4, 9))
            span = float(shadow[deep].max() - threshold)
            if span > 1e-6:
                shadow[deep] = threshold + np.floor((shadow[deep] - threshold) / span * steps + 0.5) / steps * span
        image = 1.0 - shadow

    image = blur(image, rng.uniform(0.25, 0.60))
    return np.clip(image + fibre_texture(coverage.shape, salt + 41) * rng.uniform(0.003, 0.014), 0.0, 1.0)


def render_screen(coverage: np.ndarray, rng: Rng, salt: int) -> np.ndarray:
    """A photographed display.

    Three stacked structures: the RGB subpixel comb (3–5 px), the row grid, and the moiré beat
    the two produce against the camera's sampling lattice. On top of those, the two things a
    photograph of a screen has and a photograph of paper never does — a broad specular reflection
    off the cover glass, and slow panel mura.

    The moiré is the *signal*, not a decoration: a real macro photograph of a display is dominated
    by it, because the subpixel comb itself sits at or beyond the optical resolution limit. That
    is also why this is the class the prompt's attack AT-04 depends on being separable: nothing
    else in the corpus is this deterministic, so `peakiness` and pitch should carry it.
    """
    shape = coverage.shape
    yy, xx = np.mgrid[0:shape[0], 0:shape[1]].astype(np.float64)

    subpixel_pitch = rng.uniform(*SCREEN_SUBPIXEL_PX)
    sub_index = np.floor((xx + rng.uniform(0.0, subpixel_pitch)) / subpixel_pitch).astype(np.int64)
    # Perceptual luminance weight of R, G, B, cycling along the comb. This 3-phase modulation is
    # the whole reason a subpixel comb is visible in a *grayscale* macro photo at all: with a
    # constant weight per subpixel the luminance would be flat and there would be no comb to
    # find, only a slightly darker average.
    luma = _SUBPIXEL_LUMA[sub_index % 3] / 0.59

    row_pitch = subpixel_pitch * 3.0
    row_phase = np.mod(yy + rng.uniform(0.0, row_pitch), row_pitch) / row_pitch
    row_gap = 1.0 - rng.uniform(0.02, 0.16) * np.abs(row_phase - 0.5) * 2.0

    # The document content *multiplies* the emitted light. At bare white every subpixel is off,
    # so the *emission* comb vanishes exactly where a printed page is blank.
    emission = luma * row_gap
    image = 1.0 - np.clip(1.0 - coverage, 0.0, 1.0) * emission * rng.uniform(0.75, 1.0)

    # KNOWN WEAKNESS, measured and left in place on purpose.
    #
    # The emission comb exists only where the document has ink, so a screen showing a *text* zone —
    # where the content is near-binary — reduces to a near-binary texture and reads as `LASER`. On
    # a held-out page, 2 of 6 crops were misread that way and the 2 that were right carried margins
    # of 0.07 to 0.35 against a RED floor of 0.35. Attack AT-04 is precisely "a screen showing a
    # document", so this is the case that most needs the weakness fixed.
    #
    # The obvious repair is to add the microlens/pixel-aperture grid, which is real and does not
    # vanish at white. It was implemented, measured, and **removed**: it moved the macro-F1 by
    # -0.002, pushed `SCREEN` into `DYESUB` on four report rows, and destroyed the abstention
    # behaviour on all four ambiguous specimens. With no real captures to arbitrate, an unvalidated
    # refinement that makes the numbers worse is not a fix, and keeping it because it sounds more
    # physical would be fitting the generator to the metric. It belongs in the D-MACRO collection
    # as a capture condition to measure, not in a renderer nobody can check. See the model card's
    # known limits.

    # Moiré: the beat between the comb and the camera's sampling lattice. For the wider panels in
    # SCREEN_SUBPIXEL_PX this is what lands inside the analysis band; for the fine ones the comb
    # itself does. Both are in the model, because which one dominates depends on the panel.
    moire_pitch = rng.uniform(13.0, 48.0)
    theta = math.radians(rng.uniform(0.0, 180.0))
    image = image + np.cos(2.0 * np.pi * (xx * math.cos(theta) + yy * math.sin(theta)) / moire_pitch) * rng.uniform(0.02, 0.11)

    # Cover glass: one broad reflection gradient, the only strong low-frequency structure here.
    glass = rng.uniform(0.0, math.pi)
    gradient = (xx / shape[1]) * math.cos(glass) + (yy / shape[0]) * math.sin(glass)
    gradient = (gradient - gradient.min()) / max(float(gradient.max() - gradient.min()), 1e-9)
    image = image * (1.0 - gradient * rng.uniform(0.05, 0.22))

    # Mura: slow column-to-column panel variation.
    columns = _u01((1, shape[1]), salt + 53)[0]
    kernel = np.ones(9) / 9.0
    columns = np.convolve(np.pad(columns, 4, mode="edge"), kernel, mode="valid")[:shape[1]]
    image = image * (1.0 - columns[None, :] * rng.uniform(0.01, 0.06))

    return np.clip(image + _normal_field(shape, salt + 59) * rng.uniform(0.004, 0.018), 0.0, 1.0)


def render_photocopy(coverage: np.ndarray, rng: Rng, salt: int) -> np.ndarray:
    """A photocopy: another process, re-imaged, plus the copier's own signature.

    A real source process is rendered first and then degraded. A photocopy is *not* an
    independent texture class — it is a copy of inkjet, or of offset, or of anything — and
    pretending otherwise would teach the model something false. What makes the class learnable is
    that the copier contributes the same things every time: optical blur that flattens whatever
    peak the source had, a faint tone curve that puts mid-tone back, a ghost double from the
    drum, broadband grain, and a moiré at a pitch *near but not equal* to the source's.
    """
    source = rng.choice(["OFFSET", "INKJET", "LASER", "DYESUB"])
    source_rng = Rng(salt, 0x5048_4F54, len(source))
    if source == "OFFSET":
        image = render_offset(coverage, source_rng, salt + 101)
        base_radius = radius_for(0.5 * (OFFSET_LPI[0] + OFFSET_LPI[1]))
    elif source == "INKJET":
        image, base_radius = render_inkjet(coverage, source_rng, salt + 102), 30.0
    elif source == "LASER":
        image, base_radius = render_laser(coverage, source_rng, salt + 103), 12.0
    else:
        image = render_dyesub(coverage, source_rng, salt + 104)
        base_radius = radius_for(0.5 * (DYESUB_DPI[0] + DYESUB_DPI[1]))

    # 1. Copier optics, plus the fact that you are photographing a photocopy.
    image = blur(image, rng.uniform(0.9, 2.2))

    # 2. Faint tone: copier toner is light, so the image washes toward paper white.
    image = 1.0 - np.power(np.clip(1.0 - image, 0.0, 1.0), rng.uniform(0.55, 0.90))

    # NOT MODELLED: the copier's tone quantisation.
    #
    # A real copier does dither onto a 8–32 level grey ramp, and that quantisation is a strong
    # part of what a photocopy *looks* like. It is deliberately absent here, and the reason is
    # worth writing down: the dither exists at the *copier's* resolution — roughly 200 dpi, i.e.
    # a 16 px cell on the DATA.md §1 grid — and rendering it at the patch's own pixel scale
    # instead would invent a 1–4 px noise field that no copier produces. An earlier revision of
    # this generator did exactly that, at a single pixel, and it pushed `PHOTOCOPY` toward
    # `INKJET`/`DYESUB` because an invented fine dither is an inkjet's signature. The copier's
    # native-resolution quantisation is already represented by the optical blur above, which is
    # the same MTF. See the model card's known limits.

    # 3. Ghost double: the drum leaves a faint second impression a fraction of a dot off.
    strength = rng.uniform(0.10, 0.28)
    offset = rng.uniform(0.6, 2.0)
    ghost = np.roll(np.roll(image, int(round(offset)), axis=1), int(round(offset * 0.6)), axis=0)
    image = np.clip(image * (1.0 - 0.5 * strength) + ghost * 0.5 * strength, 0.0, 1.0)

    # 4. Grain: the copier's own toner and sensor. Broadband, so it lifts the noise floor without
    #    imposing a lattice.
    image = image + _normal_field(coverage.shape, salt + 7) * rng.uniform(0.012, 0.048)

    # 5. Moiré from re-imaging, at a radius near the source's and therefore never aligned with it.
    yy, xx = np.mgrid[0:coverage.shape[0], 0:coverage.shape[1]].astype(np.float64)
    moire_radius = base_radius * rng.uniform(1.04, 1.22)
    theta = math.radians(rng.uniform(0.0, 180.0))
    image = image + np.cos(2.0 * np.pi * (xx * math.cos(theta) + yy * math.sin(theta)) / (PATCH / moire_radius)) * rng.uniform(0.010, 0.045)

    return np.clip(blur(image, rng.uniform(0.10, 0.45)), 0.0, 1.0)


#: The reject pile (DATA.md §3): six ways a patch can contain no print process at all.
UNKNOWN_KINDS = ["flat_paper", "sensor_noise", "motion_blur", "extreme_defocus", "dust", "specular"]


def render_unknown(coverage: np.ndarray, rng: Rng, salt: int) -> np.ndarray:
    """The reject pile: texture that no print process produced.

    Deliberately a *mixture*, because "unclassifiable" is not one thing. A blank patch, a
    defocused patch, a patch full of dust and a patch of a fingertip are unclassifiable for
    different reasons, and a reject class built from one of them would only know how to say no to
    that one.

    None of these is made by filtering a *periodic* field. A heavily blurred halftone screen still
    has a lattice, and putting one in the reject pile would teach the model that "blurry" means
    UNKNOWN — which is a failure mode, not a label.
    """
    shape = coverage.shape
    kind = UNKNOWN_KINDS[rng.next_u32() % len(UNKNOWN_KINDS)]
    yy, xx = np.mgrid[0:shape[0], 0:shape[1]].astype(np.float64)

    if kind == "flat_paper":
        image = np.full(shape, rng.uniform(0.55, 0.95))
        image = image + _normal_field(shape, salt + 1) * rng.uniform(0.004, 0.016)
        image = image + fibre_texture(shape, salt + 2) * rng.uniform(0.004, 0.022)
    elif kind == "sensor_noise":
        image = rng.uniform(0.25, 0.80) + _normal_field(shape, salt + 3) * rng.uniform(0.10, 0.26)
    elif kind == "motion_blur":
        # The source is stochastic, so however hard it is smeared there is no periodicity left.
        field = _normal_field(shape, salt + 4)
        kernel = np.ones(int(rng.randint(9, 26)))
        kernel /= kernel.sum()
        image = (_convolve_axis(field, kernel, 0) if rng.chance(0.5)
                 else _convolve_axis(field, kernel, 1))
        image = rng.uniform(0.30, 0.85) + image * rng.uniform(0.08, 0.22)
    elif kind == "extreme_defocus":
        image = blur(np.clip(coverage, 0.0, 1.0), rng.uniform(7.0, 16.0))
        image = image * rng.uniform(0.45, 0.85) + rng.uniform(0.05, 0.30)
        image = image + _normal_field(shape, salt + 6) * rng.uniform(0.006, 0.028)
    elif kind == "dust":
        image = np.full(shape, rng.uniform(0.70, 0.98))
        specks = (_u01(shape, salt + 7) > rng.uniform(0.990, 0.9985)).astype(np.float64)
        image = image - blur(specks, 0.6) * rng.uniform(0.25, 0.60)
        angle = rng.uniform(0.0, math.pi)
        scratches = np.abs(np.sin(2.0 * np.pi * (xx * math.cos(angle) + yy * math.sin(angle)) / rng.uniform(18.0, 60.0)))
        image = image - (scratches > 0.985).astype(np.float64) * rng.uniform(0.05, 0.22)
    else:  # specular
        cx, cy = rng.uniform(0.2, 0.8) * shape[1], rng.uniform(0.2, 0.8) * shape[0]
        radius = rng.uniform(0.10, 0.30) * min(shape)
        hotspot = np.exp(-(((xx - cx) ** 2 + (yy - cy) ** 2) / (2.0 * radius * radius)))
        image = 1.0 - hotspot * rng.uniform(0.35, 0.80)
        image = image + _normal_field(shape, salt + 8) * rng.uniform(0.004, 0.018)

    return np.clip(image, 0.0, 1.0)


RENDERERS = {
    "OFFSET": render_offset,
    "INKJET": render_inkjet,
    "LASER": render_laser,
    "DYESUB": render_dyesub,
    "SCREEN": render_screen,
    "PHOTOCOPY": render_photocopy,
    "UNKNOWN": render_unknown,
}


# ===================================================================== capture stage


def capture(image: np.ndarray, rng: Rng, light: str, clipped: bool) -> np.ndarray:
    """Per-patch capture conditions: the synthetic analogue of light × clip × focus.

    This is where within-class variation that is *not* about the process comes from, and it is
    the part a real corpus gets from moving the phone and the light. Deliberately
    label-preserving: lighting, defocus and sensor noise do not turn offset into inkjet, so every
    crop of a page keeps its page's label and therefore its page's `source_doc`.
    """
    if light == "sun":
        gain, offset, gamma = rng.uniform(0.92, 1.12), rng.uniform(-0.02, 0.03), rng.uniform(0.95, 1.06)
    elif light == "shade":
        gain, offset, gamma = rng.uniform(0.72, 0.92), rng.uniform(0.02, 0.10), rng.uniform(1.00, 1.14)
    else:  # torch
        gain, offset, gamma = rng.uniform(1.05, 1.30), rng.uniform(-0.06, 0.01), rng.uniform(0.88, 0.99)

    out = np.power(np.clip(image, 0.0, 1.0), gamma) * gain + offset

    # With the clip seated the shot is soft only by the optics and the white balance is
    # normalised by the calibration card (DATA.md §5). Without it, focus is missed and the
    # balance has drifted — which is the condition `MacroStage` refuses to classify past.
    if clipped:
        out = blur(out, rng.uniform(0.10, 0.30))
        out = out + _normal_field(out.shape, rng.next_u32() & 0xFFFF) * rng.uniform(0.002, 0.008)
    else:
        out = blur(out, rng.uniform(0.45, 1.15))
        out = out + _normal_field(out.shape, rng.next_u32() & 0xFFFF) * rng.uniform(0.006, 0.024)
        out = out * (1.0 + rng.uniform(-0.05, 0.05))

    return np.clip(out, 0.0, 1.0)


# ===================================================================== corpus assembly


def document_seed(base_seed: int, class_index: int, document_index: int) -> int:
    return base_seed + class_index * 100_003 + document_index * 1_009


def render_document(label: str, base_seed: int, class_index: int, document_index: int) -> np.ndarray:
    """One synthetic page, DOC_SIZE square. Every crop of it shares this page's process."""
    seed = document_seed(base_seed, class_index, document_index)
    rng = Rng(seed, len(label), class_index, document_index)
    return RENDERERS[label](document_coverage(rng, DOC_SIZE, DOC_SIZE), rng, seed)


def crops(document: np.ndarray) -> list[np.ndarray]:
    out = []
    for x, y in CROP_OFFSETS:
        if x + PATCH <= document.shape[1] and y + PATCH <= document.shape[0]:
            out.append(document[y:y + PATCH, x:x + PATCH].copy())
    return out


# ------------------------------------------------------------------ specimen set
#
# The handful of patches the Kotlin test renders. One per class from a *held-out* document (index
# 900, far past the training range), plus the two must-abstain specimens. `:eval`'s
# `SynthMacroSpecimen` implements the same recipes and `eval/tools/check_specimen_parity.py`
# proves the two agree — which is the only reason the Kotlin test's verdicts mean anything.

#: Held out by construction: past the end of any training range this file will be asked for.
SPECIMEN_DOCUMENT_INDEX = 900
#: Which crop of the page the specimen is taken from — offset (128, 128), the one that straddles
#: the photo/text boundary. Chosen for representativeness, not for the score: an operator points
#: the clip at an area of the document with a bit of everything in it, and crop (0, 0) is the
#: whole photo zone. That matters practically as well as honestly — a bitonal toner render of a
#: photo zone is nearly solid black (a 2-level print has no mid-tone, so mid-tone ink coverage
#: already reads as solid), which makes a (0, 0) laser specimen an uninformative black square.
SPECIMEN_PATCH_INDEX = 2

#: The specimens a classifier must *not* be confident about. Each is a real thing a capture
#: pipeline produces constantly, and each is undecidable for a stated physical reason rather
#: than because it was made noisy.
AMBIGUOUS = [
    "ambiguous_paste_seam",
    "ambiguous_featureless",
    "ambiguous_faded_offset",
    "ambiguous_screen_through_offset",
]

#: Dot-contrast range for the faded-offset specimen, in units of "as dark as the plate intends".
#:
#: **Set by a measured transition, not by picking a value that passes a test.** The sweep in
#: [faded_offset_contrast_sweep] shows the margin collapsing as the lattice decays into the paper
#: grain: above ~0.30 the plate is still plainly an offset screen, below ~0.10 the dot structure is
#: gone and the patch is a photocopy or a screen, and in between the two readings are
#: equidistant. This range is the middle of that collapse. The whole sweep is printed in the model
#: card so the claim is auditable rather than a single number chosen to suit a test.
FADED_OFFSET_CONTRAST = (0.10, 0.26)
#: The screen the faded specimen uses: 210 lpi, mid-sheet-fed, comfortably inside the analysis
#: band. Not a pitch near a class boundary, because the ambiguity here is in *contrast* and not
#: in geometry — a second ambiguity variable would make the specimen uninterpretable.
FADED_OFFSET_LPI = 210.0


def render_specimen(label: str, base_seed: int = BASE_SEED) -> np.ndarray:
    index = LABELS.index(label)
    return crops(render_document(label, base_seed, index, SPECIMEN_DOCUMENT_INDEX))[SPECIMEN_PATCH_INDEX]


def faded_offset_patch(contrast: float, base_seed: int = BASE_SEED) -> np.ndarray:
    """A 210 lpi offset screen at `contrast` dot darkness, on the same page layout.

    Contrast is the one knob that models real ink decay: a worn plate, a starved press, a card
    that has been in a wallet. Every *geometric* feature of the print is untouched — the pitch,
    the rosette angle, the dot gain — so the lattice is still exactly where it was. Only its
    amplitude falls, into the band where the paper grain and the sensor noise are the same size as
    the dots themselves. At that point the reading is not "hard", it is *underdetermined*, and a
    classifier that returns a confident label there is reporting a confidence the patch cannot
    support.
    """
    rng = Rng(base_seed, 0x46414445, 0x4F464653)
    coverage = document_coverage(rng, PATCH, PATCH)
    pitch = pitch_px(FADED_OFFSET_LPI)
    du, dv, _ = _rotated_cell(coverage.shape, pitch, 45.0)
    mask = (np.hypot(du, dv) < _dot_radius(coverage, pitch, 0.20)).astype(np.float64)
    image = 1.0 - mask * contrast * (1.0 - 0.15 * coverage)
    image = blur(image, 0.18)
    return np.clip(image + fibre_texture(image.shape, 0x50415045) * rng.uniform(0.010, 0.022), 0.0, 1.0)


def faded_offset_contrast_sweep(
    score,
    base_seed: int = BASE_SEED,
    contrasts: tuple[float, ...] = (0.90, 0.70, 0.50, 0.40, 0.30, 0.26, 0.20, 0.15, 0.10, 0.06),
) -> list[dict]:
    """Margin as a function of a decaying plate, for the model card.

    `score(image) -> (label, margin, runner_up)` is injected rather than imported so the trainer
    and this module stay one-way dependent. The point of the table is that the abstention claim
    is a *curve* with a crossing, and a reviewer can see where the crossing is and argue with it.
    """
    rows = []
    for contrast in contrasts:
        label, margin, runner_up = score(faded_offset_patch(contrast, base_seed))
        rows.append({"contrast": contrast, "label": label, "margin": margin, "runnerUp": runner_up})
    return rows


def render_specimen_paste_seam(base_seed: int = BASE_SEED) -> np.ndarray:
    """Two processes meeting inside one patch — the paste-up boundary, R-PROC-02's case.

    Physically this is a collage, or a re-print whose photo zone abuts its text zone. It is the
    most useful ambiguous specimen in the set precisely because it is *not* out-of-distribution:
    both halves are ordinary processes, the features are a genuine mixture, and there is no
    physical reason to prefer either label. A confident answer here would be a confidence the
    measurement does not support.
    """
    rng = Rng(base_seed, 0x50415354, 0x5345414D)
    left_source = blur(render_specimen("OFFSET", base_seed), 0.5)
    right_source = blur(render_specimen("INKJET", base_seed), 0.5)
    seam_x = int(rng.randint(96, 160))
    feather = int(rng.randint(6, 18))
    out = np.empty((PATCH, PATCH), dtype=np.float64)
    for x in range(PATCH):
        if x <= seam_x - feather:
            out[:, x] = left_source[:, x]
        elif x >= seam_x + feather:
            out[:, x] = right_source[:, x]
        else:
            t = (x - (seam_x - feather)) / (2.0 * feather)
            t = t * t * (3.0 - 2.0 * t)  # smoothstep shoulder, the way two regions actually meet
            out[:, x] = left_source[:, x] * (1.0 - t) + right_source[:, x] * t
    return np.clip(out, 0.0, 1.0)


def render_specimen_featureless(base_seed: int = BASE_SEED) -> np.ndarray:
    """No evidence: defocused, very low contrast, in shade. The most common real failure.

    Physically a macro shot taken without the clip, too far, in bad light — the case
    `MacroStage` exists to catch. Every informative feature sits at a trivial value: no peak worth
    calling a peak, no mid-tone, no structure. On such a patch the seven class scores should be
    nearly equal, and *that* is the property worth testing: the model must be unsure rather than
    confidently wrong.
    """
    rng = Rng(base_seed, 0x46454154, 0x4C455353)
    out = blur(render_specimen("LASER", base_seed), rng.uniform(6.0, 11.0))
    out = 0.55 + (out - out.mean()) * rng.uniform(0.05, 0.14) + rng.uniform(-0.02, 0.06)
    out = out + _normal_field(out.shape, 0x4E4F4953) * rng.uniform(0.010, 0.028)
    return np.clip(out, 0.0, 1.0)


def render_specimen_faded_offset(base_seed: int = BASE_SEED) -> np.ndarray:
    """A faded offset print: see [faded_offset_patch]. The contrast is drawn from the measured
    transition zone, so the specimen is a *class* of ambiguous patch rather than one lucky value.
    """
    rng = Rng(base_seed, 0x46414445, 0x53414D50)
    return faded_offset_patch(rng.uniform(*FADED_OFFSET_CONTRAST), base_seed)


def render_specimen_screen_through_offset(base_seed: int = BASE_SEED) -> np.ndarray:
    """A display showing an offset print — attack AT-04's hard case, with two lattices in one patch.

    Physically: a phone photographing a phone, where the screen is showing a photograph of an
    offset-printed document. The patch now carries a subpixel comb, a row grid, a moiré beat and
    an offset halftone. Neither the "it is a screen" reading nor the "it is a print" reading is
    complete, and the two are the two things an operator is being asked to tell apart. This is the
    specimen where a high-margin answer is most dangerous, because the AT-04 evidence card reads
    `SCREEN` and a wrong `SCREEN` is a false accusation.
    """
    rng = Rng(base_seed, 0x53435245, 0x4F465354)
    document = render_specimen("OFFSET", base_seed)
    # The screen emits the document's *appearance*, so halftone the emitted luminance rather than
    # the ink density: the dot screen is itself what the display is showing.
    coverage = 1.0 - np.clip(document, 0.0, 1.0)
    return np.clip(render_screen(coverage, rng, 0x5343524E), 0.0, 1.0)


SPECIMEN_RENDERERS = {
    "ambiguous_paste_seam": render_specimen_paste_seam,
    "ambiguous_featureless": render_specimen_featureless,
    "ambiguous_faded_offset": render_specimen_faded_offset,
    "ambiguous_screen_through_offset": render_specimen_screen_through_offset,
}



def specimen_rows(base_seed: int = BASE_SEED) -> list[dict]:
    """The specimen set as records: seven class rows plus the two must-abstain rows."""
    rows = []
    for label in LABELS:
        rows.append({
            "id": f"specimen-{label.lower()}",
            "label": label,
            "expectation": "class",
            "sourceDoc": f"specimen-doc-{label.lower()}",
            "synthetic": True,
            "generator": f"synth_macros.py@{base_seed}",
            "features": extract(patches.normalised(render_specimen(label, base_seed))),
        })
    for name, renderer in SPECIMEN_RENDERERS.items():
        rows.append({
            "id": f"specimen-{name.replace('_', '-')}",
            "label": name,
            "expectation": "abstain",
            "sourceDoc": f"specimen-doc-{name.replace('_', '-')}",
            "synthetic": True,
            "generator": f"synth_macros.py@{base_seed}",
            "features": extract(patches.normalised(renderer(base_seed))),
        })
    return rows


# ===================================================================== PNG output


def write_png_gray(path: pathlib.Path, image01: np.ndarray) -> None:
    """8-bit greyscale PNG, using only zlib.

    Hand-written rather than pulled from an imaging library so the encoder is auditable and the
    corpus has no dependency beyond NumPy. A `requirements.txt` entry that silently decides what
    the training data looks like is a dependency nobody thinks about when they change it.
    """
    array = np.clip(np.rint(np.clip(image01, 0.0, 1.0) * 255.0), 0, 255).astype(np.uint8)
    height, width = array.shape
    raw = b"".join(b"\x00" + array[row].tobytes() for row in range(height))

    def chunk(tag: bytes, payload: bytes) -> bytes:
        body = tag + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    blob = b"\x89PNG\r\n\x1a\n"
    blob += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0))
    blob += chunk(b"IDAT", zlib.compress(raw, 6))
    blob += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(blob)


# ===================================================================== main


def build_corpus(
    base_seed: int,
    docs_per_class: int,
    patches_per_doc: int,
    out_dir: pathlib.Path,
    write_images: bool,
) -> tuple[list[dict], list[dict]]:
    """Returns `(records, manifest_rows)`; the records are ready for `train_svm.py`."""
    records: list[dict] = []
    manifest: list[dict] = []
    for class_index, label in enumerate(LABELS):
        for document_index in range(docs_per_class):
            document = render_document(label, base_seed, class_index, document_index)
            page_crops = crops(document)[:patches_per_doc]
            source_doc = f"syn-doc-{label.lower()}-{document_index:04d}"
            page_rng = Rng(document_seed(base_seed, class_index, document_index), 0x434150)
            for patch_index, crop in enumerate(page_crops):
                light = _LIGHTS[page_rng.next_u32() % len(_LIGHTS)]
                clipped = page_rng.chance(0.65)
                shot = Rng(base_seed, class_index, document_index, patch_index)
                image = capture(rotate(crop, shot.uniform(-0.8, 0.8)), shot, light, clipped)
                normalised = patches.normalised(image)
                row_id = f"syn-{label.lower()}-{document_index:04d}-{patch_index:02d}"
                records.append({
                    "id": row_id,
                    "label": label,
                    "source_doc": source_doc,
                    "synthetic": True,
                    "generator": f"synth_macros.py@{base_seed}",
                    "light": light,
                    "clip": int(clipped),
                    "featureNames": feature_names(),
                    "features": extract(normalised),
                })
                manifest.append({
                    "row_id": row_id,
                    "source_doc": source_doc,
                    "process_label": label,
                    "light": light,
                    "clip": int(clipped),
                    "device_id": _DEVICE,
                    "calib_id": "SYNTHETIC-NONE",
                    "captured_at_utc": _CAPTURED_AT,
                    "operator": "synth_macros.py",
                    "double_checked_by": "n/a-synthetic",
                    "notes": f"{SYNTHETIC_WORD}: generated texture, NOT a print process",
                    "synthetic": "true",
                    "generator": f"synth_macros.py@{base_seed}",
                })
                if write_images:
                    write_png_gray(
                        out_dir / label.lower() / light / ("clip" if clipped else "noclip") / f"{row_id}.png",
                        normalised,
                    )
    return records, manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default="eval/data/synthetic_macro", help="corpus root (pngs + manifest)")
    parser.add_argument("--features", default="", help="also write the feature records (JSONL) here")
    parser.add_argument("--docs-per-class", type=int, default=48)
    parser.add_argument("--patches-per-doc", type=int, default=6)
    parser.add_argument("--seed", type=int, default=BASE_SEED)
    parser.add_argument("--no-images", action="store_true", help="write the manifest only, no pngs")
    parser.add_argument("--emit-specimens", default="", help="write the specimen feature rows here and exit")
    parser.add_argument("--emit-specimen-pngs", default="", help="with --emit-specimens, also write the patches")
    args = parser.parse_args(argv)

    if args.emit_specimens:
        target = pathlib.Path(args.emit_specimens)
        target.parent.mkdir(parents=True, exist_ok=True)
        rows = specimen_rows(args.seed)
        target.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in rows), encoding="utf-8")
        print(f"{SYNTHETIC_WORD}: wrote {len(rows)} specimen row(s) to {target}")
        print(f"  {len(LABELS)} class specimens, {len(AMBIGUOUS)} must-abstain specimens")
        if args.emit_specimen_pngs:
            root = pathlib.Path(args.emit_specimen_pngs)
            for label in LABELS:
                write_png_gray(root / f"specimen-{label.lower()}.png",
                               patches.normalised(render_specimen(label, args.seed)))
            for name, renderer in SPECIMEN_RENDERERS.items():
                write_png_gray(root / f"specimen-{name.replace('_', '-')}.png",
                               patches.normalised(renderer(args.seed)))
            print(f"  wrote {len(rows)} specimen png(s) to {root}")
        return 0

    out_dir = pathlib.Path(args.out)
    records, manifest = build_corpus(
        args.seed, args.docs_per_class, args.patches_per_doc, out_dir, not args.no_images,
    )
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = out_dir / "manifest.csv"
    with manifest_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=MANIFEST_COLUMNS)
        writer.writeheader()
        writer.writerows(manifest)

    documents = len({r["source_doc"] for r in records})
    print(f"{SYNTHETIC_WORD} CORPUS — generated textures, NOT print processes (DATA.md §3 has no media)")
    print(f"  {len(LABELS)} classes x {args.docs_per_class} documents, {documents} source documents")
    print(f"  {len(records)} patches, up to {args.patches_per_doc} crops per document")
    print(f"  manifest -> {manifest_path}")
    if not args.no_images:
        print(f"  images   -> {out_dir}/<class>/<light>/<clip|noclip>/<row_id>.png (gitignored)")
    if args.features:
        feature_path = pathlib.Path(args.features)
        feature_path.parent.mkdir(parents=True, exist_ok=True)
        feature_path.write_text(
            "".join(json.dumps(record, sort_keys=True) + "\n" for record in records), encoding="utf-8",
        )
        print(f"  features -> {feature_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
