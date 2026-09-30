# KASOTI — hardware/ : the ₹300 macro clip and the printable artefacts

This folder holds everything needed to **build the clip** and to **make the
things it is used on**: the calibration card and the SPECIMEN mock-card
specification. It is deliberately a *build-and-measure* folder, not a marketing
folder. Nothing here claims a number that was not measured.

| File | What it is | Owner of the next edit |
|---|---|---|
| `clip_bom.md` | bill of materials for one ₹300 clip (macro + polarizer + UV + light shroud) | Android+Hw |
| `calibration_card.tex` | LaTeX source for the printable calibration card | Vision/Data |
| `calibration_card.pdf` | **compiled output** — print this, two copies, laminated | Vision/Data |
| `print_targets.md` | printable SPECIMEN mock-card specification (the brief for whoever designs them) | Demo/Docs + Design |

---

## 1. What the clip is, and why it exists

KASOTI's load-bearing layer is **R-PROC**: how the document was *made*
(DESIGN.md §4, FUSION.md, SPEC FR-C3). A home inkjet, an office laser, a dye-sub
PVC card printer and a phone screen produce measurably different micro-structure
at the 2 mm texture scale — halftone dot pitch, ink spread into paper fibre,
toner gloss, pixel pitch. That difference is invisible to the naked eye at
30 cm and obvious to a 50 KB model looking at a 256 px gray patch
(DESIGN.md §4: FFT radial-peak score + LBP-59 → linear SVM).

**A bare phone camera cannot see it.** At arm's length a 12 MP sensor pixel is
~1.5 µm, and the optics give you maybe 4–8 px across a 2 mm patch at normal
working distance. The macro layer is band-limited by optics, not by the sensor,
and the patch is lost in noise and in whatever the ambient light is doing.
`Q_BLUR`, `Q_GLARE`, `Q_BRIGHT_MIN/MAX` (ThresholdRegistry) exist precisely
because the un-clipped capture is unreliable — EVAL §9 publishes a
"macro without-clip" number *specifically to justify the clip-mandatory rule*.

So the clip is a **fixed-focus macro illuminator**: a cheap lens stack that
turns "hold the phone near a document and hope" into "seat the shroud on the
patch, get a known working distance, a known field of view, and a known,
near-unidirectional light."

### The four parts, and what each one buys

| Part | What it does | Failure it removes |
|---|---|---|
| **Macro lens** | short focal length + small aperture → a real magnification at ~8–12 mm standoff, enough depth of field that a curved document is not half-blurred | resolution collapse; the whole R-PROC layer greys out |
| **Linear polarizer** (film + analyser) | kills specular glare from glossy laminate, ID overlay film and varnished PVC; makes a tilted document readable instead of a white streak | `Q_GLARE` false-blur → GREY on every laminated card |
| **UV (long-wave, 365–405 nm)** | excites the optical brighteners and the base-fluorescence difference between print processes and substrate | the "genuine paper is white, dye-sub PVC is not" cue, which is often the cheapest discriminator available |
| **Light shroud** | matte black walls + an LED ring at a shallow angle | ambient light at the finals is uncontrolled (DEMO.md §1: "mixed ambient"); without the shroud the texture you measure is a mix of texture and room |

**What it must achieve optically** (the acceptance list, not a wish list):

1. **Magnification ≈ 4–8× at the nominal standoff**, over a field of view of
   roughly **6 × 4.5 mm** — enough for one 2 mm macro patch plus margin, so the
   operator seats it once instead of hunting.
2. **Working distance 8–12 mm**, fixed and repeatable to ±1 mm. The shroud
   physically rests on the document, so the distance is a *mechanical*
   property, not a skill. A clip you have to focus by hand is a clip the
   operator will skip.
3. **Depth of field ≥ 1.5 mm** at that distance, so a gently curved card is not
   split into a sharp band and a blurred band.
4. **F/8-ish effective aperture and no diffraction mush** — a cheap singlet
   plus a cheap aperture plate is acceptable; a plastic asphere with a stopped
   down to f/16 is not.
5. **Illumination at 30–45° incidence**, not on-axis, so surface relief
   (raised print, embossing, intaglio) casts a shadow that the camera can see.
   This is a *shading* requirement, not a brightness one.
6. **Spectral honesty.** The white LED must not be so blue that it fabricates a
   UV response; the UV channel must be **filterable out** so one capture can
   serve both the gray/UV fusion columns (FUSION.md's UV row) without the LED
   contaminating the texture features. Hence: white LED swappable to "UV off"
   and "UV on" positions, and a UV-pass / visible-block filter in the path.
7. **Reproducible, not a one-off.** Three clips (ROADMAP M0.7) that agree with
   each other on the calibration card, so a threshold tuned on clip A is not a
   lie on clip B.

### Honest limits (say these out loud)

- A clip is an **auxiliary**, not a gate: QA_BANK already commits to this. UV is
  "auxiliary where supported"; stamp inspection is manual zoom assist.
- It helps against **printers and screens**, not against a state-level offset
  press with genuine microprint (AT-01's residual column, FUSION.md). No ₹300
  part changes that.
- It **cannot** make the no-clip path good. The without-clip number is published
  (EVAL §9) precisely because it is worse.

---

## 2. Build and measure (order matters)

1. Read `clip_bom.md`. Buy **three** of everything cheap — a clip that works is
   a demo; three that work is a spares kit for the finals (DEMO.md §4).
2. **Re-quote before the finals purchase.** Prices in the BOM are indicative
   estimates from public listings, not measured quotes, and the currency/region
   mix is not uniform.
3. Assemble. Photograph the assembly and drop the photos in `hardware/`
   (ROADMAP M0.7 exit criterion).
4. **Calibrate each clip against `calibration_card.pdf`** (DATA.md §5). Record
   the calibration id with every macro patch you collect (DATA.md §3 logs
   `calib-id`) — an uncalibrated capture is not evidence, it is a mood.
5. Field test on the two named devices. "3 clips work on 2 phones" is the
   M0.7 gate; a clip that only works on one phone model is a clip we re-make.

## 3. Printing

- `calibration_card.pdf` — A4, print **two**, laminate both. It is consumed by
  wear and by the venue.
- The SPECIMEN mock cards are specified in `print_targets.md`. **This folder
  does not contain finished artwork** — the spec is the brief; producing the
  print master is design work with its own owner (HANDOFF.md §2). Print 10 sets
  (DEMO.md §4) plus one pre-glued spare.
- Big diagonal `SPECIMEN — KASOTI TEST` on everything. Not for legal reasons —
  for the "judge hands us their own ID at the booth" reason (THREAT_MODEL §5,
  DATA.md §1: no real-PII pixels leave a consent boundary).

## 4. Do not do these

- **Do not put a brand-locked part in the BOM.** Every line is a generic
  commodity class with a specification, so a re-quote can substitute freely. A
  clip that cannot be rebuilt in a different city in a different year is not a
  prototype, it is a supply chain.
- **Do not tune a threshold by eye on a document.** Calibration card first,
  then the number (BUILD.md §7 troubleshooting: "never eyeball-tune thresholds").
- **Do not store live face bitmaps by default** to make the macro demo easier
  (AGENTS.md §5). Macro patches are 2 mm texture crops, visually checked for
  legible PII before commit (DATA.md §1).
