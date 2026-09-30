# KASOTI — Red-Team Runbook (REDTEAM.md)

> ⚠️ **This has never been run.** There is no `eval/runs/redteam-<date>/` directory, no
> scoreboard, no attack set, and no failure gallery. Every attack below is a *plan*. The
> prerequisites are also unmet: no SPECIMEN artwork, no built clip, no `:app-android` build, no
> devices, no embedder, and no macro dataset. `DEMO.md` §4 and §6 both depend on the gallery
> this produces.

## 1. Setup (M3, full day; crash-variant: half day, starred attacks only)
Split: FORGERS (build attacks) vs OPERATORS (run lanes) + a SCRIBE (log every attempt: id,
expected, actual, artifact-ids). **Solo variant (actual):** the maintainer builds each attack,
blinds them by sealing them in an envelope with a written expected verdict, then runs the lanes
against the sealed set without re-reading it — the scribe log becomes a written record kept
before the attempt, not a memory of it. Rotation and "no coaching mid-attempt" cannot be
delegated; they have to be procedural, or the exercise measures enthusiasm. Venue-light variety
(sun/shade/indoor) is mandatory and currently impossible (no device).

## 2. Attack catalog (ID, name, needs, pass = system response)
**Print/process:** RT-P1* inkjet full reprint · RT-P2 laser reprint · RT-P3 photocopy-of-genuine · RT-P4 SCREEN-shows-doc* · RT-P5 PVC-card-printer reprint (outsource if available; else deck-note) · RT-P6 genuine + ballpoint DOB edit · RT-P7 high-res scan + microprint check.
**Photo/stamp:** RT-S1* glue photo-swap · RT-S2 tape-laminate swap · RT-S3 genuine-piece collage (two SPECIMENs) · RT-S4 stamp photocopy-paste · RT-S5 stamp-ink hand-draw.
**Data:** RT-D1* MRZ 1-digit DOB mutant · RT-D2 recomputed-check-digit fake (red-team computes valid digits!) · RT-D3 QR-mutated payload · RT-D4 wrong-key QR · RT-D5 QR-from-another-card (valid sig, wrong person)* · RT-D6 expired + altered-expiry.
**Face:** RT-F1* wrong-person live · RT-F2* printed-face spoof · RT-F3* screen-face spoof · RT-F4 lookalike pair (closest teammates) · RT-F5 mask/glasses/occlusion · RT-F6 5-year-old photo (volunteer archive if available).
**Diary/ops:** RT-E1* enroll-with-fake (tests supervisor control) · RT-E2 alias-next-day (different name/DOB) · RT-E3 impossible-travel (two posts same day, fixtures+live mix) · RT-E4 GREY-gaming (deliberate blur ×4) · RT-E5 watchlist plant (HQ → QR → lane) · RT-E6 sync-tamper (flip byte; expect reject) · RT-E7 replay old bundle (expect dupe-skip) · RT-E8 model-file swap (expect loader refuse) · RT-E9 threshold-file edit (expect version alarm in audit).
**Environment:** RT-X1 harsh sun glare · RT-X2 night-torch only · RT-X3 shaky-hand (no clip seat) · RT-X4 low battery-saver mode (perf check).

## 3. Scoring
Per attempt: CAUGHT (right verdict + right evidence) / PARTIAL (AMBER instead of RED etc.) / MISS (wrong-GREEN or wrong-RED). Scoreboard: `eval/runs/redteam-<date>/scoreboard.csv` — **this directory does not exist for any date.** Targets: 100% on starred data-QR-sync attacks; publish the rest honestly.

**What is already attackable without a device, and is therefore the only part that could be
drilled first.** RT-D1/D2/D3/D4/D6 (MRZ and QR data mutations), RT-E6/E7/E8/E9 (sync tamper,
replay, model swap, threshold edit) and the "worn genuine card" policy can all be exercised
against `:core` and the `:app-desktop` CLI today, with no hardware. That is roughly a third of
the catalog and it is the part with the strongest existing test coverage — which is also exactly
why a drill there is the *least* informative: RT-D1's expected behaviour is already asserted by
a passing test. The drills that would teach us something (RT-P*, RT-S*, RT-F*, RT-E1/E3/E4)
all need a device, a clip, a print set and an embedder. Say that in the debrief rather than
banking the easy half as coverage.

## 4. Failure gallery (the deck's best slides)
One slide per MISS + notable PARTIAL: attack photo → system output screenshot → cause (1 line) → fix-or-acknowledge (shipped fix w/ run-id, or accepted residual w/ attacker-cost note). Bring printed gallery to Q&A unprompted.

⚠️ **The gallery is empty.** `QA_BANK.md` Q9 ("what beats you?") and `DEMO.md` §3's closing beat
both assume it exists. Until it does, the honest answer to that question is the published
rejection list in `docs/spikes/01-face-model.md` §2.1 plus the open-risk register in
`docs/STATUS.md` §5 — which is a real answer, and a better one than a gallery of misses we chose
ourselves, but it is not the same thing.

## 5. Rules of engagement
SPECIMEN/consented data only · no real third-party IDs · no prod keys · stop-line on P0 bug (fix+retest same day) · scribe's log is final.
