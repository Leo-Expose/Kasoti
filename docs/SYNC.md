# KASOTI — Sync Protocol `kasoti-sync/1` (SYNC.md) — normative

## 1. Properties
Append-only event log · idempotent merge · file/USB-first · HMAC-authenticated (prototype profile) · deterministic merge (same inputs → same diary) · quarantine on model/generation mismatch. No deletes propagate (purge is local-only + logged; retention enforced per-device).

## 2. Identity & crypto (prototype profile P-HMAC)
- Deployment secret: 256-bit HMAC key, generated at provisioning (`scripts/provision.sh`), installed via QR (single, supervised) or USB; stored Android-Keystore / desktop OS-protected file (0600 + doc note).
- Envelope: `{"v":1,"device":"<uuid>","seqStart":n,"seqEnd":m,"ts":"…","records":[…],"hmac":"hex(HMAC-SHA256(key, canonical(records+header)))"}`.
- Canonical form: UTF-8, sorted keys, no whitespace (test vectors in `eval/fixtures/sync/vectors.json`).
- Replay: receiver tracks `maxSeq[device]`; reject `seq ≤ maxSeq` (report counts, no crash). Clock skew >5 min → warning in merge report.
- Device-id collision (cloned installs): provisioning assigns uuid; import rejects bundles from own device-id; drill in BUILD troubleshooting.

## 3. Record schemas
```json
// crossing event (diary)
{"t":"cross","id":"evt_<ulid>","seq":412,"device":"uuid","post":"RAXAUL","ts":"…Z",
 "track":"aadhaar","nameSha":"hex(sha256(norm-name|dob|salt=deployment-id))",
 "dob":"1998-..-..","docHash":"hex(sha256(canonical-doc-fields))",
 "embModel":"emb_v1@sha256:<64hex>","emb":"base64(128×int8)","q":0.87,
 "verdict":"GREEN","findings":["M-OK","Q-SIG-OK"],"prev":"hex(chain)"}
// trust enrollment / revocation
{"t":"enroll","id":"…","subjectRef":"nameSha","emb":"…","approvedBy":"sup:<id>","validTill":"…","prev":"…"}
{"t":"revoke","id":"…","ref":"enroll-id|evt-id","reason":"…","by":"sup:<id>"}
// watchlist entry (small; QR-distributable)
{"t":"watch","id":"wl_<ulid>","emb":"…","label":"WANTED:…","source":"HQ-2026-…","expires":"…"}
```
Notes: `dob` is operationally needed for alias display; deployments MAY hash it (then alias-on-dob disabled — config flag). Embeddings int8-quantized (scale in bundle header `qScale`).

## 4. Merge algorithm (deterministic)
1. Verify HMAC → reject file (log, no partial apply).
2. Check `embModel` == local generation → else whole-file quarantine dir + report (never partial).
3. For each record: skip if `id` seen; append in (ts, device, id) order; update chain tip + maxSeq.
4. Recompute derived flags (alias/travel/facilitator) incrementally; watchlist matches evaluated at next search + immediately for top-risk (config).
5. Emit `MergeReport{accepted, dupes, rejected, quarantined, skewWarnings, newFlags[]}` shown in UI + logged.

## 5. Transports (priority order)
1. File/USB (`kasoti_sync_<post>_<date>_<seq>.json`, split at 5 MB).
2. HTTPS POST to console inbox (P1; same envelope; mutual secret header; server = desktop console, not cloud).
3. QR: watchlist + HMAC-provisioning ONLY (≤2 QRs); diary deltas NEVER via QR.
Filename + manifest (`manifest.json`: file hashes) for multi-file days.

## 6. Provisioning & ops
New device: generate device-uuid → install HMAC via supervised QR/USB → exchange `HELLO` bundle (seq 0) both ways → verify merge reports. Lost device: rotate deployment secret (re-provision all; old bundles verify under old key id `kid` retained read-only for history). `kid` field in envelope (default `k1`).

## 7. Upgrade path (post-prototype, documented now)
P-DEVICEKEYS: per-device Ed25519 keys, HQ-signed device certs, envelope signatures replace HMAC; P-HMAC bundles remain verifiable (read-only `kid`). Rotation: seq-monotonic re-key event. No protocol break (bump `v:2`, dual-verify during migration).

## 8. Test plan (in `:core` + eval)
Golden vectors (canonical+HMAC) · mutation tests (flip byte → reject) · replay/dupe/idempotence · out-of-order merge determinism (3 shuffles, same tip) · model-mismatch quarantine · skew warnings · 100k-record merge perf budget (<5 s desktop, <15 s phone; else streaming-merge P1).
