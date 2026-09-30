"""Synthetic macro patches, generated identically in Kotlin and in NumPy.

`dev.kasoti.eval.suites.SpectrumPatch` is the Kotlin side. This file is the NumPy side, and
the two must produce the same 8-bit pixels or `cross_check.py` is comparing two different
images and proving nothing.

Every recipe is a closed-form function of integer arithmetic. There is deliberately no RNG:
`kotlin.random.Random` is not contractually stable across platforms, so a generator that used
it could not be reproduced here, and a cross-check whose fixtures drift is worse than no
cross-check at all. Determinism is the property that makes the comparison meaningful.

The recipes are geometry, not data. Nothing here is a photograph of a document, and nothing
here is a crop of one (DATA.md §1) — which is why these are committed as code and the
real corpus is not committed at all.
"""

from __future__ import annotations

import numpy as np

#: `:core`'s spectrum path requires exactly this size (`dev.kasoti.factory.Spectrum.PATCH`).
PATCH = 256

#: Kept in step with `dev.kasoti.eval.suites.SpectrumPatch.SEED`.
SEED = 20_260_931


#: **Every recipe returns float32.** `np.where(cond, 0.85, 0.25)` builds a *float64* array
#: from two Python float scalars, which silently runs the whole pipeline one precision above
#: `:core` — and the Sobel threshold then lands on a different number of pixels than the
#: device counts. The dtype is pinned explicitly on every recipe for that reason.

#: Column and row index vectors, in the array orientation `:core` uses.
#:
#: `GrayImage` stores row-major and `operator get(x, y)` reads `pixels[y * width + x]`, so a
#: patch is indexed `[y, x]`. Getting these two the wrong way round is invisible in every
#: symmetric recipe and produces a *different image* in every asymmetric one — which is how
#: the speckle recipe's LBP histogram came out different while its pixel count and mean
#: matched exactly.
_XS = np.arange(PATCH)[None, :]  # varies along columns
_YS = np.arange(PATCH)[:, None]  # varies along rows


def lattice() -> np.ndarray:
    """A square lattice at period 8: one strong, deliberate spectral peak."""
    on = (_XS % 8 < 4) & (_YS % 8 < 4)
    return np.where(on, np.float32(0.85), np.float32(0.25))


def rosette() -> np.ndarray:
    """A 45-degree rosette, the characteristic offset screen."""
    d = np.abs((_XS + _YS) % 12 - 6)
    return np.where(d < 3, np.float32(0.8), np.float32(0.3))


def speckle() -> np.ndarray:
    """Stochastic dither from an integer hash: no lattice, a broad band."""
    h = _hash2(_XS, _YS, 0)
    return np.where(h > 0.6, np.float32(0.75), np.float32(0.2))


def gradient() -> np.ndarray:
    """A left-to-right ramp, constant down each column, as a low-pass control.

    Broadcast to a full patch because `:core`'s `SpectrumPatch.gradient` returns a
    256x256 image, and a (256, 1) array would be rejected by the FFT with a shape error
    rather than a value error — a confusing way to learn that a ramp is one-dimensional.
    """
    column = (_XS / (PATCH - 1) * 0.8 + 0.1).astype(np.float32)
    return np.broadcast_to(column, (PATCH, PATCH)).astype(np.float32).copy()


def flat() -> np.ndarray:
    """Constant. A spectrum with no peak — the `ProcessLabel.UNKNOWN` control."""
    return np.full((PATCH, PATCH), np.float32(0.5), dtype=np.float32)


def subpixel() -> np.ndarray:
    """A 3px screen lattice with a per-row phase, the `SCREEN` recipe."""
    return np.where((_XS // 3 + _YS // 3) % 2 == 0, np.float32(0.9), np.float32(0.1))


def photocopy() -> np.ndarray:
    """Low-contrast, blurred copy of a lattice: the `PHOTOCOPY` recipe."""
    on = (_XS % 16 < 8) & (_YS % 16 < 8)
    return np.where(on, np.float32(0.62), np.float32(0.48))


#: Recipe name -> generator, in `ProcessLabel` declaration order. `train_svm.py` iterates
#: this; `cross_check.py` iterates this. Both must agree with Kotlin's `SpectrumPatch`.
RECIPES: dict[str, "callable"] = {
    "OFFSET": rosette,
    "INKJET": speckle,
    "LASER": lattice,
    "DYESUB": gradient,
    "SCREEN": subpixel,
    "PHOTOCOPY": photocopy,
    "UNKNOWN": flat,
}

#: `dev.kasoti.factory.MacroFeatures` widths, mirrored so a width change is a loud failure
#: here rather than a silent reshape of the model's input.
NUMERIC_FEATURES = 7
LBP_BINS = 59
TOTAL = NUMERIC_FEATURES + LBP_BINS


_MASK32 = np.int64(0xFFFFFFFF)
_SIGN_BIT = np.int64(1 << 31)
_MOD32 = np.int64(1 << 32)


def _i32(value: np.ndarray) -> np.ndarray:
    """Wrap to a *signed* 32-bit integer, which is what a Kotlin `Int` holds.

    The signedness matters as much as the width. Kotlin's `h shr 13` is an **arithmetic**
    shift: once bit 31 is set, the fill bits are ones. NumPy's `>>` on a non-negative int64
    fills with zeros, so masking to `0xFFFFFFFF` and shifting silently produces a different
    number — and a different dither pattern, not a small drift. Hence the two's-complement
    round trip here rather than a plain `& 0xFFFFFFFF`.
    """
    wrapped = value % _MOD32
    return np.where(wrapped >= _SIGN_BIT, wrapped - _MOD32, wrapped)


def _hash2(x: np.ndarray, y: np.ndarray, nonce: int) -> np.ndarray:
    """A stable 32-bit integer hash, matching Kotlin's `SpectrumPatch.hash2`.

    Two things have to be reproduced exactly, and both are invisible until they are not:

    1. **32-bit wrapping at every step.** Kotlin's `Int` overflows silently; an int64
       intermediate carries on and gives different high bits.
    2. **Arithmetic, not logical, right shifts.** See [_i32].

    Getting either wrong does not produce a small numeric difference — it produces an
    entirely different image, which is why the cross-check compares features and not just a
    checksum.
    """
    h = _i32(x.astype(np.int64) * 374_761_393
             + y.astype(np.int64) * 668_265_263
             + nonce * 2_147_483_647)
    h = _i32(_i32(h ^ (h >> 13)) * 1_274_126_177)
    h = _i32(h ^ (h >> 16))
    # float32, not float64. `:core` divides by an `Int` into a `Float`, and at this magnitude
    # (values near 1.3e9) a float32 has ~64 units of resolution — so a float64 division
    # disagrees with it for a large fraction of pixels, and since the speckle recipe then
    # thresholds the result, a handful of pixels land on the wrong side of 0.6. The image
    # looks almost identical and the LBP histogram is not.
    return (h & np.int64(0x7FFFFFFF)).astype(np.float32) / np.float32(2_147_483_647)


def to_bytes(image: np.ndarray) -> np.ndarray:
    """Quantise to 8-bit, the form a platform decoder hands to `:core`.

    **Not used by the cross-check.** `:core`'s `SpectrumPatch` feeds unquantised floats to
    `GrayImage`, so the cross-check must too; quantising on one side only is exactly the kind
    of half-difference that turns a cross-check into noise. This exists because a real capture
    *is* 8-bit, and a training set built from unquantised geometry would be one step removed
    from what the device sees.
    """
    return np.clip(np.rint(image * 255.0), 0, 255).astype(np.uint8)


def normalised(image: np.ndarray) -> np.ndarray:
    """The quantised `0.0..1.0` view. See [to_bytes] on why the cross-check skips it."""
    return to_bytes(image).astype(np.float32) / 255.0
