# KASOTI — clip v2 bill of materials (BOM)

Scope: **one** clip (macro + polarizer + UV + light shroud) for one Android
phone. Target all-in cost **₹300** (DEMO.md §3, QA_BANK "Cost per post?").
Build **three** (ROADMAP M0.7: "Clip v2 build ×3"), so the per-clip cost column
below also has a ×3 column for the real purchase.

---

> ## ⚠ PRICES ARE INDICATIVE ESTIMATIVES, NOT MEASURED QUOTES
>
> Every rupee figure below is an **order-of-magnitude estimate** assembled from
> public retail listings and general knowledge of the commodity market. It is
> **not** a supplier quote, it was **not** measured from a real invoice, and the
> prices come from **mixed regions and currencies** (India / China / global
> hobbyist), so they are not directly comparable and the total is not a budget
> you should spend against.
>
> **Re-quote before the finals purchase.** Do not treat the ₹ total as a
> committed cost. If you present this to a judge as "₹300 per post", say it is
> a target built from indicative parts costs, and be ready to show the actual
> invoice — the honest version of the claim is stronger than the precise-looking
> unverified one.
>
> Also: prices move, shipping adds 10–30% on cross-border parts, and small-quantity
> buyers pay a premium over bulk. Budget headroom above is deliberately thin.

---

## 1. Part classes

**Generic commodity parts only. No brand lock-in.** Every line states a
*specification*, not a product, so any equivalent part can be substituted when
re-quoting. Where a spec is written as a class (e.g. "PC achromatic singlet,
f≈8 mm, Ø2–3 mm") any supplier meeting the class is acceptable. Vendor names are
NOT recorded here on purpose: naming a vendor in a prototype BOM is how a
prototype quietly becomes a product with a supply chain.

## 2. Bill of materials

Prices in ₹, indicative, per single unit (MOQ 1–10 assumed, cross-border
shipping not included unless stated).

| # | Class | Specification (substitutable) | Spec rationale | Qty/clip | ₹/clip (est.) | ₹×3 (est.) |
|---|---|---|---|---|---|---|
| 1 | **Macro lens — achromatic doublet** | PC or glass achromatic doublet, **f ≈ 8–10 mm**, Ø 2–3 mm, NA ≥ 0.15, 550 nm band | real magnification at short standoff; achromatic so the FFT texture features are not tinted by lateral colour | 1 | 90 | 270 |
| 2 | **Macro lens — barrel/tube** | black-anodised aluminium or brass tube, ID 6–8 mm, length 15–20 mm, matte interior | matte interior = no stray reflections (glare shows up as `Q_GLARE`) | 1 | 45 | 135 |
| 3 | **Aperture stop** | black acetate / anodised disc, Ø 2–3 mm, or 2–3 layers of black electrical tape on the tube | f/8-ish: enough DOF, not enough diffraction to blur the halftone | 1 | 5 | 15 |
| 4 | **Polarizer — film** | 25 mm linear polarizing film (iodine-based, transmission >35%) | the first element kills specular glare from laminate/overlay film | 1 | 25 | 75 |
| 5 | **Polarizer — analyser** | 25 mm linear polarizer on a thin acrylic disc, mounted **rotatable** ~30° | crossed orientation rejects the residual specular component; rotatable because optimum angle depends on the phone's own polarisation | 1 | 30 | 90 |
| 6 | **UV source (long wave)** | 365–405 nm LED, ≥20 mW optical, 5 V or 3.7 V driven, with a constant-current driver (≥20 mA) | long-wave excites optical brighteners — the process cue on paper vs dye-sub PVC | 1 | 55 | 165 |
| 7 | **UV filter (visible-block)** | 395–400 nm long-pass UV filter disc, Ø 10–12 mm, D30 ≥ 80% | makes the UV channel *separable* so one capture can serve gray + UV columns without contaminating each other | 1 | 70 | 210 |
| 8 | **White LED + lens** | warm/neutral white LED, CRI ≥ 80, 3500–4500 K, with a diffused lens (frosted PMMA) | 30–45° illumination that makes surface relief cast a shadow the camera can see | 1 | 35 | 105 |
| 9 | **Light shroud** | matte black, non-reflective: 3D-printed PETG / black-ABS sheet, or flocked black card/foam laminate, internal walls | mixed venue ambient is the demo's biggest hardware risk (RISKS R10); the shroud is what makes texture measurement repeatable |
| 9a | shroud body | Ø ~30 mm skirt, wall height 12–16 mm, inner cavity matte black, seats flat on the document | sets the working distance mechanically (±1 mm) instead of by hand |
| 9b | shroud window | clear or diffuse window Ø 12–16 mm, 1–2 mm thick acrylic, seated at 8–12 mm | fixes standoff + lets the operator see the patch to frame the shot |
| 9c | shroud light ring | LED mount ring at 30–45° incidence, 2–3 discrete positions (white / UV-off / UV-on) | the shading requirement from hardware/README §1; also makes the UV channel a *chosen* state, not an accident |
| 10 | **Spacers / frame** | 3D-printed PETG spacer ring or PCB standoff set (M2/M2.5, 5–10 mm) | cheapest way to hold the optical stack without a machined housing |
| 11 | **Clamp / seat** | spring steel or ABS clip band sized to one phone width, lined with black felt or silicone | the operator seats the shroud on the card and stops thinking about it |
| 12 | **Adhesive / fixings** | black epoxy or cyanoacrylate, PTFE tape, cable ties | consumables; also used to black out the interior baffles |
| 13 | **Weathering / optics care** | lens cloth, isopropyl alcohol, matte-black spray (matte, not gloss) | gloss re-introduces exactly the glare the polarizer removes |

**Totals (indicative, per clip):**

| Group | ₹/clip (est.) |
|---|---|
| Macro optics (1–3) | 140 |
| Polarizer (4–5) | 55 |
| UV (6–7) | 125 |
| White light + shroud + fixings (8–13, incl. 9a–c) | ~110 |
| **Sub-total, printed parts (often reused across the 3 clips)** | **~430** |
| Consumables amortised over 3 clips | ~15 |
| **Indicative all-in per clip (lens/barrel/polarizer/UV bought per clip)** | **~445** |

> **The ₹300 target is NOT met by this stack, and that is the honest number.**
> The ₹300 figure in README/QA_BANK/DEMO.md was a *target*, established before
> anyone costed a real UV filter + analyser. Two honest ways forward, both the
> lead's call, not this file's:
>
> - **Cut scope, honestly.** Drop the *analyser* (keep only the polarizer film,
>   saving ~30) and drop the dedicated UV long-pass filter by using a
>   phone-controllable UV mode (saving ~70). That lands near **₹345–350**, still
>   above 300, and it costs the "one capture, two separable channels" property
>   — which the FUSION UV row treats as *auxiliary*, not load-bearing
>   (QA_BANK: "UV = auxiliary where supported").
> - **Change the headline.** Say "**₹350–450** per clip" and defend the actual
>   invoice. A precise number we cannot substantiate is exactly the kind of
>   hand-typed claim AGENTS.md §5 forbids.
>
> Do **not** resolve this by quietly deleting rows until it reads ₹300.

## 3. Substitutions that are explicitly allowed

| If this part is unavailable | Acceptable substitute | Do NOT accept |
|---|---|---|
| PC achromatic doublet | glass achromatic doublet (heavier, same class, better transmission) | a single uncoated plano-convex — chromatic fringing lands straight on the texture features |
| dedicated UV long-pass filter | two stacked 400 nm long-pass filters (slightly dimmer, more waveband rejection) | no filter at all — then the UV channel is not separable and must be labelled auxiliary |
| 3D-printed shroud | black foam-core + flocked card, hand-cut | **gloss** black plastic — reintroduces the glare the polarizer exists to remove |
| rotatable analyser mount | fixed analyser + a documented phone-orientation rule | no analyser — glare rejection becomes luck-of-the-angle |
| 365 nm LED | 405 nm LED (record which wavelength was used per clip!) | an unspecified "UV torch" — the wavelength changes the response and breaks cross-clip comparability |

## 4. Calibration + acceptance (per clip, before it is allowed near the demo)

Ticked per clip, recorded with the clip id (this id goes into the `calib-id`
field of every macro patch, DATA.md §3):

- [ ] Magnification measured against `calibration_card.pdf` 5 mm grid — target 4–8×
- [ ] Field of view measured — target 6 × 4.5 mm
- [ ] Working distance measured with the shroud seated — 8–12 mm, ±1 mm across
      10 seat/remove cycles
- [ ] DOF ≥ 1.5 mm: place a gently curved card in, capture 3 heights
- [ ] Glare test: laminated card, ±30° tilt, no blowout, `Q_GLARE` not tripped
- [ ] UV-on capture shows base-fluorescence difference on paper vs dye-sub PVC
- [ ] UV-off capture's gray features match a UV-off capture taken without the
      clip (i.e. the filter/LED state is actually switchable)
- [ ] Sharpness bar goes green at the seated distance on **both** named phones
- [ ] Photographs of the assembly saved to `hardware/`

## 5. Open items for the owner

- [ ] Re-quote every line with a real supplier and a date; replace §2 prices
- [ ] Decide the ₹300 question (§2): cut scope, or change the headline
- [ ] Record the actual per-clip cost in `docs/STATUS.md` once measured, with
      the invoice date, so the deck cites a measurement rather than an estimate
- [ ] Confirm the UV wavelength actually used, per clip (3/4 substitutions rule)
