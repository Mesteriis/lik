"""Real-byte regressions for compiled-container and signing-record boundaries."""
import hashlib
import pathlib
import struct
import zipfile
import zlib

import test_apk as fixtures
from test_apk import ApkFixture, CATALOG, ROOT
from inspect_apk import inspect_apk, signing_block


def resource_wrapper(payload, kind):
    child = struct.pack("<HHI", 0x0001, 8, 8 + len(payload)) + payload
    return struct.pack("<HHI", kind, 8, 8 + len(child)) + child


def dex_wrapper(payload):
    data = bytearray(112) + payload
    data[:8] = b"dex\n039\0"
    struct.pack_into("<III", data, 32, len(data), 112, 0x12345678)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]))
    return bytes(data)


def signing_parts(apk):
    with zipfile.ZipFile(apk) as archive:
        central = archive.start_dir
    data = apk.read_bytes()
    size = struct.unpack_from("<Q", data, central - 24)[0]
    start = central - size - 8
    return data, start, central, data[start + 8:central - 24]


def add_signing_record(apk, output, identifier, payload):
    data, start, central, records = signing_parts(apk)
    records += struct.pack("<QI", 4 + len(payload), identifier) + payload
    size = len(records) + 24
    block = struct.pack("<Q", size) + records + struct.pack("<Q", size) + b"APK Sig Block 42"
    tail = bytearray(data[central:])
    struct.pack_into("<I", tail, len(tail) - 6, start + len(block))
    output.write_bytes(data[:start] + block + tail)


class CompiledPayloadRegressionTest(ApkFixture):
    def runtime_file(self, relative):
        return fixtures.DownloadedPayloadRegressionTest.runtime_file(self, relative)

    def built_apk(self, relative):
        path = ROOT / "app/build/outputs/apk" / relative
        if not path.is_file():
            self.skipTest("Build app and androidTest APKs before compiled-container regressions")
        return path

    def test_real_payload_wrappers_fail_for_all_variants_and_zip_methods(self):
        models = {name: self.runtime_file(name) for name in (
            "yunet-v1/model.onnx", "multilingual-text-v1/tokenizer.json",
            "multilingual-text-v1/tokenizer_config.json")}
        wrappers = [
            ("res/xml/wrapped.xml", resource_wrapper(models["yunet-v1/model.onnx"], 3)),
            ("res/xml/wrapped.xml", resource_wrapper(models["multilingual-text-v1/tokenizer_config.json"], 3)),
            ("resources.arsc", resource_wrapper(models["yunet-v1/model.onnx"], 2)),
            ("resources.arsc", resource_wrapper(models["multilingual-text-v1/tokenizer.json"], 2)),
            ("classes99.dex", dex_wrapper(models["yunet-v1/model.onnx"])),
            ("classes99.dex", dex_wrapper(models["multilingual-text-v1/tokenizer.json"])),
        ]
        app_entries = self.entries.copy()
        for relative, kind in (("debug/app-debug.apk", "app"), ("release/app-release-unsigned.apk", "app"),
                               ("androidTest/debug/app-debug-androidTest.apk", "android-test")):
            with zipfile.ZipFile(self.built_apk(relative)) as original:
                manifest = original.read("AndroidManifest.xml")
            metadata = app_entries if kind == "app" else {
                "assets/" + p.relative_to(ROOT / "app/schemas").as_posix(): p.read_bytes()
                for p in (ROOT / "app/schemas").rglob("*.json")}
            base = metadata | {"AndroidManifest.xml": manifest}
            for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                for name, payload in wrappers:
                    with self.subTest(variant=relative, compression=compression, entry=name, size=len(payload)):
                        with zipfile.ZipFile(self.path, "w", compression=compression) as archive:
                            for entry, data in (base | {name: payload}).items():
                                archive.writestr(entry, data)
                        with self.assertRaisesRegex(ValueError, "not reviewed compiler output"):
                            inspect_apk(self.path, CATALOG, kind)

    def test_duplicate_v2_record_with_real_tokenizer_config_is_rejected(self):
        payload = self.runtime_file("multilingual-text-v1/tokenizer_config.json")
        for relative, kind in (("debug/app-debug.apk", "app"),
                               ("androidTest/debug/app-debug-androidTest.apk", "android-test")):
            with self.subTest(variant=relative):
                add_signing_record(self.built_apk(relative), self.path, 0x7109871A, payload)
                with self.assertRaisesRegex(ValueError, "Duplicate APK signing record ID"):
                    inspect_apk(self.path, CATALOG, kind)

    def test_current_apksig_v2_signed_data_empty_reserved_field_is_allowed(self):
        data, start, central, _ = signing_parts(self.built_apk("androidTest/debug/app-debug-androidTest.apk"))
        signing_block(data[start:central])

    def test_nested_signing_fields_cannot_carry_unconsumed_tokenizer_bytes(self):
        payload = self.runtime_file("multilingual-text-v1/tokenizer_config.json")
        _, _, _, records = signing_parts(self.built_apk("androidTest/debug/app-debug-androidTest.apk"))
        length, _ = struct.unpack_from("<QI", records)
        v2 = records[12:8 + length]

        def lp(data):
            return struct.pack("<I", len(data)) + data

        def fields(data):
            result, offset = [], 0
            while offset < len(data):
                size = struct.unpack_from("<I", data, offset)[0]
                result.append(data[offset + 4:offset + 4 + size])
                offset += 4 + size
            self.assertEqual(len(data), offset)
            return result

        signed, signatures, key = fields(fields(fields(v2)[0])[0])
        digests, certificates, attributes, reserved = fields(signed)
        self.assertEqual(b"", attributes)
        self.assertEqual(b"", reserved)
        mutations = [
            (lp(digests) + lp(certificates) + lp(lp(struct.pack("<I", 123) + payload)) + lp(b""), signatures, key),
            (signed + lp(payload), signatures, key),
            (lp(digests) + lp(certificates) + lp(b"") + lp(payload), signatures, key),
            (lp(digests) + lp(certificates + lp(payload)) + lp(b"") + lp(b""), signatures, key),
            (lp(digests + digests) + lp(certificates) + lp(b"") + lp(b""), signatures, key),
            (signed, signatures + signatures, key),
            (signed, signatures, key + payload),
        ]
        for index, parts in enumerate(mutations):
            body = lp(lp(b"".join(lp(part) for part in parts)))
            pair = struct.pack("<QI", 4 + len(body), 0x7109871A) + body
            size = len(pair) + 24
            block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"
            with self.subTest(field=index), self.assertRaises(ValueError):
                signing_block(block)

    def test_opaque_signing_records_and_unconsumed_signer_bytes_are_rejected(self):
        payload = self.runtime_file("multilingual-text-v1/tokenizer_config.json")
        _, _, _, records = signing_parts(self.built_apk("androidTest/debug/app-debug-androidTest.apk"))
        length, identifier = struct.unpack_from("<QI", records)
        self.assertEqual(0x7109871A, identifier)
        valid_v2 = records[12:8 + length]
        for identifier, body in ((0x7109871A, payload), (0x7109871A, valid_v2 + payload),
                                 (0x6DFF800D, payload), (0xF05368C0, payload)):
            pairs = struct.pack("<QI", 4 + len(body), identifier) + body
            size = len(pairs) + 24
            block = struct.pack("<Q", size) + pairs + struct.pack("<Q", size) + b"APK Sig Block 42"
            with self.subTest(identifier=identifier, size=len(body)), self.assertRaises(ValueError):
                signing_block(block)
