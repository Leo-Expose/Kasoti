# D-USAB — usability sessions

5+ novice timed runs plus a GREY drill (EVAL.md §2). Not a corpus: field sessions whose
output is an **operational reading** — the GREY rate, and whether the quality thresholds feel
right in the hand — rather than a model metric.

**Tuned for a field-like GREY rate under 8%** (FUSION.md §5). A threshold that produces
fewer GREYs than that is not obviously better; it is a screener that quietly declines to look
at difficult captures, and the cost lands on the person in front of the camera.

## What to log per session

Participant id (pseudonymous), operator, device, per-capture: which GREY causes fired
(`G_BLUR`, `G_GLARE`, `G_DARK`, `G_POSE`, `G_OCCLUDE`, `G_OCRLOW`, `G_FOCUS`, `G_NOCLIP`),
how many retakes before a decisionable capture, and wall-clock seconds to verdict.

`A-GREY3` — three consecutive GREYs — is itself a finding, because it is the signature of
someone gaming the retake loop. `GREY_STREAK_LIMIT` is the registry value and it is tested.

`manifest.csv` columns: `session_id`, `participant_id`, `operator`, `device_id`, `calib_id`,
`started_at_utc`, `captures`, `greys`, `decisions`, `median_seconds`, `notes`
