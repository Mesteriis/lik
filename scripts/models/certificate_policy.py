"""Deliberately narrow X.509 semantics for APK signing, not a general PKIX client."""
from datetime import datetime
import hashlib
import json
from pathlib import Path

POLICY_PATH = Path(__file__).with_name("apk-signing-policy-v1.json")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def oid(node):
    require(node[0] == 0x06 and node[1], "Missing certificate OID")
    values, value, start = [], 0, True
    for byte in node[1]:
        require(not (start and byte == 0x80), "Noncanonical certificate OID")
        value = value * 128 + (byte & 0x7f)
        require(value <= 0xffffffff, "Oversized certificate OID")
        start = byte < 128
        if start:
            values.append(value)
            value = 0
    require(start and values, "Truncated certificate OID")
    first = values.pop(0)
    return ".".join(str(v) for v in ([min(first // 40, 2), first - min(first // 40, 2) * 40] + values))


def integer(node):
    value = node[1]
    require(node[0] == 0x02 and value and not value[0] & 0x80 and
            (len(value) == 1 or value[0] != 0 or value[1] & 0x80), "Invalid unsigned DER integer")
    return int.from_bytes(value, "big")


def shape(node, tag, tags=None):
    require(node[0] == tag and (tags is None or [child[0] for child in node[2]] == tags),
            "Unexpected certificate field structure")
    return node[2]


def name(node, policy):
    seen = set()
    for rdn in shape(node, 0x30):
        attributes = shape(rdn, 0x31, [0x30])
        pair = shape(attributes[0], 0x30)
        require(len(pair) == 2, "Unexpected certificate name fields")
        identifier = oid(pair[0])
        limits = policy["subjectAttributeMaximumBytes"]
        require(identifier in limits and identifier not in seen, "Unreviewed or duplicate certificate name attribute")
        seen.add(identifier)
        text = pair[1]
        require(text[0] in {0x0c, 0x13} and 0 < len(text[1]) <= limits[identifier], "Certificate name outside reviewed bounds")
        try:
            value = text[1].decode("utf-8" if text[0] == 0x0c else "ascii")
        except UnicodeDecodeError as error:
            raise ValueError("Invalid certificate name encoding") from error
        require(all(c.isalnum() or c in " .,'()&+-/@:_" for c in value), "Opaque data in certificate name")
        if identifier == "2.5.4.6":
            require(len(value) == 2 and value.isascii() and value.isalpha() and value.isupper(), "Invalid country name")
    require("2.5.4.3" in seen, "Certificate must have a common name")


def validate_certificate(certificate, public_key, parse_der):
    policy = json.loads(POLICY_PATH.read_text())
    require(policy["schemaVersion"] == 1, "Unknown signing certificate policy")
    require(len(certificate) <= policy["maximumCertificateBytes"], "Certificate exceeds reviewed metadata budget")

    def one(data, tag):
        nodes = parse_der(data)
        require(len(nodes) == 1 and nodes[0][0] == tag, "Unconsumed certificate extension/key contents")
        return nodes[0]

    outer = shape(one(certificate, 0x30), 0x30, [0x30, 0x30, 0x03])
    fields = list(shape(outer[0], 0x30))
    version = 0
    if fields and fields[0][0] == 0xa0:
        version = integer(shape(fields.pop(0), 0xa0, [0x02])[0])
    require(version in policy["certificateVersions"] and len(fields) in {6, 7}, "Unreviewed certificate version or extra fields")
    serial, algorithm, issuer, validity, subject, spki = fields[:6]
    require(0 < integer(serial) and len(serial[1]) <= policy["maximumSerialBytes"], "Invalid certificate serial")
    signature_algorithm = oid(shape(algorithm, 0x30)[0])
    require(signature_algorithm in policy["certificateSignatureAlgorithms"] and algorithm == outer[1],
            "Unreviewed or mismatched certificate signature algorithm")
    parameters = algorithm[2][1:]
    require(not parameters or len(parameters) == 1 and parameters[0] == (0x05, b"", []), "Opaque certificate algorithm parameters")
    name(issuer, policy)
    name(subject, policy)
    require(issuer == subject, "Only self-signed APK signing certificates are supported")
    dates = []
    for timestamp in shape(validity, 0x30):
        require(timestamp[0] in {0x17, 0x18}, "Unreviewed certificate time field")
        expected = 13 if timestamp[0] == 0x17 else 15
        require(len(timestamp[1]) == expected and timestamp[1][:-1].isdigit() and timestamp[1][-1:] == b"Z", "Invalid certificate time")
        dates.append(datetime.strptime(timestamp[1].decode(), "%y%m%d%H%M%SZ" if expected == 13 else "%Y%m%d%H%M%SZ"))
    require(len(dates) == 2 and dates[0] < dates[1], "Invalid certificate validity interval")
    key = one(public_key, 0x30)
    require(spki == key, "Certificate/SPKI bytes differ")
    key_algorithm, bits = shape(key, 0x30, [0x30, 0x03])
    require(bits[1] and bits[1][0] == 0, "Invalid public-key bit string")
    algorithm_fields = shape(key_algorithm, 0x30)
    key_oid = oid(algorithm_fields[0])
    if key_oid == "1.2.840.113549.1.1.1":
        require(algorithm_fields[1:] == [(0x05, b"", [])], "Unreviewed RSA parameters")
        modulus, exponent = shape(one(bits[1][1:], 0x30), 0x30, [0x02, 0x02])
        key_bits, exponent_value = integer(modulus).bit_length(), integer(exponent)
        require(key_bits in policy["rsaModulusBits"] and 3 <= exponent_value <= 0xffffffff and exponent_value % 2 == 1,
                "Unreviewed RSA key size/exponent")
        require(signature_algorithm.startswith("1.2.840.113549.") and outer[2][1][:1] == b"\0" and
                len(outer[2][1]) == key_bits // 8 + 1, "Invalid RSA certificate signature")
    elif key_oid == "1.2.840.10045.2.1":
        require(len(algorithm_fields) == 2, "Unreviewed EC parameters")
        curve = oid(algorithm_fields[1])
        require(curve in policy["ecUncompressedPointBytes"] and bits[1][1:2] == b"\x04" and
                len(bits[1]) == policy["ecUncompressedPointBytes"][curve] + 1, "Unreviewed EC point/curve")
        require(signature_algorithm.startswith("1.2.840.10045.") and outer[2][1][:1] == b"\0", "Invalid EC certificate signature")
        for coordinate in shape(one(outer[2][1][1:], 0x30), 0x30, [0x02, 0x02]):
            require(0 < integer(coordinate) and len(coordinate[1]) <= 67, "Invalid EC signature integer")
    else:
        raise ValueError("Unreviewed signing public-key algorithm")
    key_identifier = hashlib.sha1(bits[1][1:]).digest()
    if len(fields) == 7:
        require(version == 2, "Extensions require X.509 v3")
        extensions = shape(shape(fields[6], 0xa3, [0x30])[0], 0x30)
        seen = set()
        for extension in extensions:
            parts = list(shape(extension, 0x30))
            require(len(parts) in {2, 3}, "Invalid certificate extension structure")
            identifier = oid(parts.pop(0))
            require(identifier in policy["extensionSemantics"] and identifier not in seen,
                    "Unreviewed or duplicate certificate extension: " + identifier)
            seen.add(identifier)
            if len(parts) == 2:
                require(parts.pop(0) == (0x01, b"\xff", []), "Invalid extension critical flag")
            require(parts[0][0] == 0x04, "Invalid extension value")
            value = parts[0][1]
            if identifier == "2.5.29.14":
                require(one(value, 0x04)[1] == key_identifier, "Subject key identifier must match the key")
            elif identifier == "2.5.29.35":
                require(shape(one(value, 0x30), 0x30) == [(0x80, key_identifier, [])], "Authority key identifier must match the key")
            elif identifier == "2.5.29.19":
                require(not shape(one(value, 0x30), 0x30), "Signing certificate must be non-CA without path length")
            elif identifier == "2.5.29.15":
                require(one(value, 0x03)[1] == b"\x07\x80", "Key usage must be digitalSignature only")
            elif identifier == "2.5.29.37":
                usages = shape(one(value, 0x30), 0x30, [0x06])
                require(oid(usages[0]) == "1.3.6.1.5.5.7.3.3", "Extended key usage must be code signing only")
            else:
                raise ValueError("Signing policy has an extension without a semantic validator")
