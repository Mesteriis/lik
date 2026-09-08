"""Real certificate-extension payloads and supported release signing stay external."""
import os
from pathlib import Path
import shutil
import subprocess

import test_apk as fixtures
from test_apk import ApkFixture, CATALOG, ROOT
from compiled_apk import build_tools_directory
from inspect_apk import inspect_apk
from apk_signing import reject_known_artifacts


class CertificatePayloadTest(ApkFixture):
    def runtime_file(self, relative):
        return fixtures.DownloadedPayloadRegressionTest.runtime_file(self, relative)

    def run_command(self, *args):
        result = subprocess.run([str(a) for a in args], capture_output=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr.decode(errors="replace"))

    def sign(self, source, payload=None, ec=False):
        directory = Path(self.temp.name)
        configuration = directory / "fixture.cnf"
        extensions = ("basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature\n"
                      "extendedKeyUsage=codeSigning\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid:always\n")
        if payload is not None:
            extensions += "1.2.3.4=DER:" + payload.hex() + "\n"
        configuration.write_text("[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=ext\n"
                                 "[dn]\nCN=Lik Release Test\nO=Lik\nC=ES\n[ext]\n" + extensions)
        openssl = shutil.which("openssl")
        if not openssl:
            self.skipTest("Certificate regression requires openssl")
        key, certificate = directory / "fixture-key.pem", directory / "fixture-cert.pem"
        key_options = ("-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1") if ec else ("-newkey", "rsa:2048")
        self.run_command(openssl, "req", "-x509", *key_options, "-nodes", "-sha256", "-days", "2",
                         "-config", configuration, "-keyout", key, "-out", certificate)
        private_der = directory / "fixture-key.pk8"
        self.run_command(openssl, "pkcs8", "-topk8", "-nocrypt", "-in", key, "-outform", "DER", "-out", private_der)
        signer = build_tools_directory() / "apksigner"
        self.run_command(signer, "sign", "--key", private_der, "--cert", certificate,
                         "--alignment-preserved", "true",
                         "--min-sdk-version", "36", "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                         "--v3-signing-enabled", "false", "--v4-signing-enabled", "false", "--out", self.path, source)
        self.run_command(signer, "verify", "--min-sdk-version", "36", self.path)
        return self.path

    def apk_variants(self):
        for relative, kind, variant in (("debug/app-debug.apk", "app", "debug"),
                                        ("release/app-release-unsigned.apk", "app", "release"),
                                        ("androidTest/debug/app-debug-androidTest.apk", "android-test", "debugAndroidTest")):
            path = ROOT / "app/build/outputs/apk" / relative
            if not path.is_file():
                if os.environ.get("LIK_MODEL_CACHE"):
                    self.fail("Build all APKs before the explicitly configured real-byte regression: " + str(path))
                self.skipTest("Build all APK variants for certificate regression")
            yield path, kind, variant

    def test_real_hf_tokenizer_in_certificate_extension_is_rejected_for_all_apks(self):
        payload = self.runtime_file("multilingual-text-v1/tokenizer_config.json")
        for source, kind, variant in self.apk_variants():
            with self.subTest(variant=variant):
                apk = self.sign(source, payload)
                self.assertIn(payload, apk.read_bytes())
                with self.assertRaises(ValueError):
                    inspect_apk(apk, CATALOG, kind, variant)

    def test_normal_release_certificate_is_accepted_for_all_apks(self):
        for source, kind, variant in self.apk_variants():
            with self.subTest(variant=variant):
                try:
                    report = inspect_apk(self.sign(source), CATALOG, kind, variant)
                except ValueError as error:
                    self.fail("Standard release signing must pass: " + str(error))
                self.assertTrue(report["metadataOnly"])
                self.assertTrue(report["signatureVerified"])

    def test_unreviewed_extension_is_rejected_even_without_known_hf_bytes(self):
        for source, kind, variant in self.apk_variants():
            with self.subTest(variant=variant):
                with self.assertRaises(ValueError):
                    inspect_apk(self.sign(source, b"unreviewed opaque extension"), CATALOG, kind, variant)

    def test_normal_ec_release_certificate_is_accepted(self):
        source = next(path for path, _, variant in self.apk_variants() if variant == "release")
        self.assertTrue(inspect_apk(self.sign(source, ec=True), CATALOG, "app", "release")["signatureVerified"])

    def test_fingerprint_scan_covers_all_offsets_without_model_cache_reads(self):
        payload = self.runtime_file("multilingual-text-v1/tokenizer_config.json")
        artifact = next(file for component in CATALOG["components"] for file in component["artifacts"]
                        if file["path"] == "multilingual-text-v1/tokenizer_config.json")
        for offset in (0, 1, 127, 512, 65536 - len(payload)):
            with self.subTest(offset=offset), self.assertRaisesRegex(ValueError, "Known HF artifact bytes"):
                reject_known_artifacts(b"\0" * offset + payload + b"\0" * (65536 - offset - len(payload)), [artifact])
        reject_known_artifacts(b"\0" * 65536, [artifact])
