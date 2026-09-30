# `manifests/calib/` — per-device calibration, and nothing else

One directory per device id, each containing the `device_calib.json` that device's guided
calibration-card routine (DATA.md §5) last wrote. The harness looks up
`manifests/calib/<device-id>/device_calib.json`, where the host-side harness uses the
`TEST-HARNESS-JVM` id — a name, not a serial, so a laptop with no calibration fails closed
rather than quietly borrowing a phone's gains.

This directory is **empty on purpose** and there is no placeholder to delete later. That is
the honest state of the repository: no device has been calibrated, so `full` refuses macro
metrics and says why. Writing a file here by hand to make a suite run would produce a
calibrated-looking macro number that is a fiction, and the fiction would outlive the reason
for it.
