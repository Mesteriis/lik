# APK payload policy

Model weights and tokenizers are downloaded from Hugging Face through the planned Settings flow. No app or instrumentation APK may contain them. `inspect_apk.py` checks every entry and the complete physical ZIP envelope; filename extensions alone never establish trust.

## Compiled Android containers

The checked-in [compiler policy](../scripts/models/apk-compiled-policy-v1.json) contains complete size/SHA-256 receipts for debug, release and debugAndroidTest resources, DEX and baseline profiles. The receipts come from AAPT2/D8/R8 compiler intermediates, not inspected APKs. Each APK must match one complete variant inventory, including every manifest/XML/ARSC/PNG entry, every DEX exactly once and all baseline profiles. Reordering ZIP entries or changing STORE/DEFLATE compression does not change the byte receipts. Wrapping model bytes in valid-looking headers, adjusting lengths/checksums or appending opaque data changes the full receipt and fails.

Pinned Android SDK build-tools 36.0.0 add independent format validation. `aapt2 dump resources` requires exactly the expected package and file inventory, with no `raw` resource type even under an obfuscated filename. `aapt2 dump xmltree` parses the manifest and every compiled XML. ART `dexdump -d -h -s` runs default full verification and traversal; checksum-only and ignore-verification modes are not used. Exact full-file receipts prevent tolerated unknown chunks or trailing data from becoming unaccounted bytes. Complete consumption does not depend on a tool's exit code alone.

The [content policy](../scripts/models/apk-content-policy-v1.json) separately pins native libraries, dependency metadata, licenses and original PNG content. Repository manifests/license notices and Room test schemas must match exact trusted bytes. A bounded AGP Git metadata grammar permits changing commit IDs without arbitrary additional fields. No `res/raw` payload boundary or unknown blob fallback exists.

## Signing and ZIP envelope

Every signing block record ID must be unique. The current policy supports the single-signer, single-certificate, single-algorithm v2 format emitted by these builds plus one all-zero padding record. Every length-prefixed sequence, signer, digest, signature, certificate DER and SubjectPublicKeyInfo DER must be consumed completely. Digest/algorithm IDs and lengths must agree. The one reserved empty field emitted by AOSP apksig is accepted only as an empty field; no other trailing bytes are allowed. `apksigner verify --min-sdk-version 36` then verifies the cryptographic binding to the APK.

Unknown records, signing attributes, source stamps, v3/v3.1/rotation, alternate algorithms, extra certificates and multisigner packaging fail closed until their exact structures and use are deliberately supported and reviewed. This is a packaging policy for the currently produced APKs, not a claim to support every valid Android signing format. Unsigned release builds remain supported. Neither cryptographic success alone nor a known block ID permits opaque record bodies: Android's verifier can accept the first v2 block while ignoring a duplicate.

ZIP prefix/trailer/comment/extra-field payloads, duplicate or overlapping entries, hidden deflate bodies and unreferenced nonzero data are rejected. Empty alignment and Zipflinger deleted-entry padding are allowed only when their complete contents are zero. Standalone `apksigner --alignment-preserved true` may add fewer than 4096 zero bytes immediately before the footer-located signing block; those bytes are separately accounted for and may contain no data. Compressed physical and total uncompressed APK sizes remain limited to 250 MiB.

## Certificate metadata and supported release signing

The [reviewed signing policy](../scripts/models/apk-signing-policy-v1.json) applies to debug, androidTest and signed release without enrolling a particular developer key. X.509 v1/v3 certificates must have matching issuer/subject and SPKI, bounded standard name/serial/time fields and supported RSA or named-curve EC keys. Allowed name attributes are CN, C, L, ST, O and OU; opaque/control characters and unknown attributes fail. Unique IDs, extra certificate fields and arbitrary algorithm parameters fail. The supported optional extensions are fully decoded: non-CA BasicConstraints, digitalSignature KeyUsage, codeSigning ExtendedKeyUsage, and subject/authority key identifiers matching the actual public key. Unknown/duplicate OIDs, opaque extension values, issuer chains, DSA and RSA-PSS certificate keys require a separate deliberate policy implementation/review. No normal build updates the policy or pins its current signing identity.

Exact size/SHA-256 scans cover **every offset of the complete signing block**, including certificate extensions, names, SPKI, signatures and field boundaries, against every fitting immutable HF artifact receipt. This uses the checked-in catalog, never the model cache. Blocks are bounded to 64 KiB; larger artifacts cannot fit. Other APK content remains covered by full entry/compiler receipts and the strict physical ZIP boundary, so no arbitrary whole-APK substring heuristic replaces those checks. Certificate structure alone and `apksigner` alone are insufficient: both accepted the real tokenizer bytes in an otherwise valid custom X.509 extension before this fix.

Ordinary AGP debug/test certificates, a standard self-signed RSA-4096 PKCS12 release keystore, and an EC P-256 release certificate were accepted in regression checks. Production signing can use the existing external `keystore.properties` Gradle configuration with a supported certificate, or explicitly sign the unsigned release while retaining its reviewed ZIP alignment:

```sh
keytool -genkeypair -alias lik-release -keystore /external/lik-release.p12 -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 \
  -dname "CN=Lik Release, O=Lik, C=ES"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks /external/lik-release.p12 \
  --alignment-preserved true --min-sdk-version 36 --v1-signing-enabled false \
  --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false \
  --out /external/release-output/lik-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
python3 scripts/models/inspect_apk.py --directory /external/release-output --kind app --variant release
```

Passwords are entered through the standard tool prompts; no signing material belongs in Git. The documented keystore route was exercised with a disposable test identity, which was then removed. The [Android signing documentation](https://developer.android.com/studio/publish/app-signing) and [RFC 5280](https://www.rfc-editor.org/rfc/rfc5280) define the underlying certificate/signing formats; this policy deliberately accepts a narrower, fully interpreted set of fields.

## Deliberate receipt updates

Normal builds only read policies. They never enroll their own outputs or refresh hashes. Android source/schema/Gradle inputs are fingerprinted; changes fail with a stale-receipt error until a developer reviews a separate candidate. New variants require an explicit compiler-path mapping. Changing only documentation or the Git revision does not invalidate compiled bytes.

After an intentional Android source/resource/dependency change, first generate compiler intermediates without packaging:

```sh
./gradlew processDebugResources mergeExtDexDebug mergeLibDexDebug mergeProjectDexDebug generateDebugGlobalSynthetics \
  processDebugAndroidTestResources mergeExtDexDebugAndroidTest mergeLibDexDebugAndroidTest \
  mergeProjectDexDebugAndroidTest generateDebugAndroidTestGlobalSynthetics \
  optimizeReleaseResources minifyReleaseWithR8 compileReleaseArtProfile
python3 scripts/models/compiled_apk.py --output /external/review-candidate.json
```

The helper takes no APK input and refuses to overwrite the trust anchor. It reads only explicit compiler intermediate paths, runs SDK parsers, records source/toolchain provenance and checks that sources did not change during preparation. Review the source diff, complete resource semantics, compiler paths and resulting receipts before manually replacing the checked-in policy. Rebuild all APKs, run regression tests and compare a candidate from a clean `--no-build-cache` compilation before committing. Do not use a policy change to approve downloaded data or arbitrary resources. Dependency/PNG content-policy changes require their own provenance review.

SDK parser binaries must match a complete reviewed distribution. macOS receipts were measured from the installed SDK; Linux receipts were independently read from the official 63,737,259-byte [Google SDK ZIP](https://dl.google.com/android/repository/build-tools_r36_linux.zip), verified against the published repository SHA-1 and an actual SHA-256. The archive and exact per-tool hashes are in the compiler policy. Linux binaries were hash-verified on macOS; Linux execution/CI was not run in this review. Another host/toolchain fails closed until its official distribution is reviewed. `--build-tools` selects an explicit SDK directory; Gradle passes its configured SDK automatically.

## Verification and sources

Regression constructors use real hash-verified tokenizer/YuNet bytes from the external cache, never Git fixtures. They cover XML/ARSC/DEX wrappers for app debug/release and instrumentation, both STORE and DEFLATE, duplicate signing records and nested signature-field carriers. Legitimate launcher PNGs, complete real compiler inventories and actual signed APKs must still pass. The exact runs and APK sizes are in [VERIFICATION.md](VERIFICATION.md).

The signing structure follows the official [v2 specification](https://source.android.com/docs/security/features/apksigning/v2). The reserved empty field is evidenced by [AOSP V2SchemeSigner at `184702d9d18877edf9e5296c4e191cf0aa2b5fbb`](https://android.googlesource.com/platform/tools/apksig/+/184702d9d18877edf9e5296c4e191cf0aa2b5fbb/src/main/java/com/android/apksig/internal/apk/v2/V2SchemeSigner.java). SDK inspection commands are described in [AAPT2 documentation](https://developer.android.com/tools/aapt2) and [apksigner documentation](https://developer.android.com/tools/apksigner); installed tools and the official [SDK package metadata](https://dl.google.com/android/repository/repository2-3.xml) supply exact distribution provenance. The [v3 specification](https://source.android.com/docs/security/features/apksigning/v3) describes the additional structures that this policy intentionally rejects rather than treating as opaque bytes.
