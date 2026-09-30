# `fixtures/qr/` — D-QR vectors

**Signatures are not committed, and cannot be.** The harness stub generates a fresh RSA
keypair per JVM, so a signature on disk would be unverifiable on the next run. The D-QR
fixtures are therefore built *in-process* at gate time: `:eval`'s QR suite constructs the
payload bytes from a fixed seed, signs them, mutates them, and verifies all of it inside one
process.

What may be committed here is the payload *structure* — field names, the `key=value&…`
encoding, the reference-number format — for a reviewer who wants to read the corpus without
running it. Regenerate it with:

```bash
./gradlew :eval:run --args="--verbose"     # the QR suite prints its outcome table
```

Test keys: every key this harness uses is generated in-process from
`HarnessStubSignatureVerifier` and never written to disk. DATA.md §7's rule — prod-key slots
stay EMPTY, test keys are named so they cannot be mistaken for production keys — is satisfied
by there being no key file at all. `TestPublicKey.fileName` produces `TEST_<keyId>.der` for
the day a key does have to be written down, and `KeyProvenance` makes "test" a type rather
than a naming convention someone can forget.
