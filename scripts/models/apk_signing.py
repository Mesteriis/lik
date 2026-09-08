"""Fail-closed framing for the v2 signatures emitted by Lik's current build.

Cryptographic/X.509 verification is additionally performed by SDK apksigner.
See https://source.android.com/docs/security/features/apksigning/v2 .
Unsupported signing schemes/attributes require a reviewed implementation first.
"""
import hashlib
import json
from pathlib import Path
import struct

from certificate_policy import validate_certificate

V2 = 0x7109871A
PADDING = 0x42726577
DIGEST_LENGTHS = {0x0101: 32, 0x0102: 64, 0x0103: 32, 0x0104: 64,
                  0x0201: 32, 0x0202: 64, 0x0301: 32}


def reject_known_artifacts(data, artifacts):
    # The only variable binary envelope outside exact-receipt ZIP entries is
    # bounded to 64 KiB. Scan EVERY offset with whole-artifact size/SHA, including
    # DER primitive contents and field boundaries. No artifact/cache bytes needed.
    by_size = {}
    for artifact in artifacts:
        if 0 < artifact["size"] <= len(data):
            by_size.setdefault(artifact["size"], {})[bytes.fromhex(artifact["sha256"])] = artifact["path"]
    view = memoryview(data)
    for size, hashes in by_size.items():
        for offset in range(len(data) - size + 1):
            match = hashes.get(hashlib.sha256(view[offset:offset + size]).digest())
            require(match is None, "Known HF artifact bytes in APK signing envelope: " + str(match))


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Reader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def take(self, size):
        require(0 <= size <= len(self.data) - self.offset, "Truncated APK signing field")
        value = self.data[self.offset:self.offset + size]
        self.offset += size
        return value

    def uint32(self):
        return struct.unpack("<I", self.take(4))[0]

    def field(self):
        return self.take(self.uint32())

    def end(self):
        require(self.offset == len(self.data), "Unconsumed bytes in APK signing structure")


def singleton(data):
    # Lik does not use multiple signers, unused alternate algorithms or chains.
    # Requiring one prevents apksigner's preferred-signature selection from
    # leaving an alternative signature/certificate as an unchecked carrier.
    reader = Reader(data)
    value = reader.field()
    reader.end()
    require(value, "Empty APK signing sequence")
    return value


def der_nodes(data, depth=0):
    require(depth <= 24, "DER nesting exceeds signing metadata budget")
    reader, nodes = Reader(data), []
    while reader.offset < len(data):
        tag = reader.take(1)[0]
        require(tag & 0x1f != 0x1f and tag != 0, "Unsupported DER tag")
        size = reader.take(1)[0]
        if size & 0x80:
            count = size & 0x7f
            require(1 <= count <= 4, "Indefinite or oversized DER length")
            encoded = reader.take(count)
            size = int.from_bytes(encoded, "big")
            require(encoded[0] != 0 and size >= 128, "Noncanonical DER length")
        value = reader.take(size)
        children = der_nodes(value, depth + 1) if tag & 0x20 else []
        if tag == 0x03:
            require(value and value[0] <= 7 and (value[0] == 0 or
                    len(value) > 1 and value[-1] & ((1 << value[0]) - 1) == 0), "Invalid DER bit string")
        nodes.append((tag, value, children))
    reader.end()
    return nodes


def der_sequence(data, child_tags):
    require(0 < len(data) <= 16 * 1024, "DER signing field exceeds metadata budget")
    nodes = der_nodes(data)
    require(len(nodes) == 1 and nodes[0][0] == 0x30 and
            [node[0] for node in nodes[0][2]] == child_tags, "Unexpected DER signing structure")


def algorithm_value(data):
    reader = Reader(singleton(data))
    algorithm, value = reader.uint32(), reader.field()
    reader.end()
    require(algorithm in DIGEST_LENGTHS, "Unsupported APK signature algorithm")
    return algorithm, value


def v2_signature(data):
    signer = Reader(singleton(singleton(data)))
    signed = Reader(signer.field())
    signatures, public_key = signer.field(), signer.field()
    signer.end()
    digest_algorithm, digest = algorithm_value(signed.field())
    certificate = singleton(signed.field())
    attributes = signed.field()
    # AOSP V2SchemeSigner appends one reserved empty length-prefixed field.
    # It is absent from the published v2 schema; only exactly four zero bytes
    # are admitted, never a nonempty field or further unconsumed bytes.
    if signed.offset < len(signed.data):
        require(not signed.field(), "Nonempty reserved APK signing field")
    signed.end()
    require(not attributes, "Signing attributes are unsupported; no opaque attribute payloads allowed")
    signature_algorithm, signature = algorithm_value(signatures)
    require(digest_algorithm == signature_algorithm and len(digest) == DIGEST_LENGTHS[digest_algorithm],
            "APK digest/signature algorithm mismatch")
    if signature_algorithm < 0x0200:
        require(len(signature) in {128, 256, 384, 512, 1024, 2048}, "Invalid RSA signature length")
    else:
        der_sequence(signature, [0x02, 0x02])
    der_sequence(certificate, [0x30, 0x30, 0x03])
    der_sequence(public_key, [0x30, 0x03])
    validate_certificate(certificate, public_key, der_nodes)


def signing_block(data, artifacts=None):
    require(32 <= len(data) <= 64 * 1024 and data[-16:] == b"APK Sig Block 42",
            "Unexpected bytes before ZIP central directory")
    require(struct.unpack_from("<Q", data)[0] == len(data) - 8 == struct.unpack_from("<Q", data, len(data) - 24)[0],
            "Invalid APK signing block length")
    reader, seen = Reader(data[8:-24]), set()
    while reader.offset < len(reader.data):
        size = struct.unpack("<Q", reader.take(8))[0]
        require(size >= 4, "Invalid APK signing record length")
        record = Reader(reader.take(size))
        identifier = record.uint32()
        require(identifier not in seen, "Duplicate APK signing record ID")
        seen.add(identifier)
        payload = record.take(size - 4)
        record.end()
        if identifier == V2:
            v2_signature(payload)
        elif identifier == PADDING:
            require(not any(payload), "Payload in APK signing padding")
        else:
            raise ValueError("Unsupported APK signing record; no opaque records allowed: " + hex(identifier))
    reader.end()
    require(V2 in seen, "Missing supported APK signature record")
    if artifacts is None:
        catalog = json.loads((Path(__file__).resolve().parents[2] / "models/catalog-v1.json").read_text())
        artifacts = [file for component in catalog["components"] for file in component["artifacts"]]
    reject_known_artifacts(data, artifacts)
