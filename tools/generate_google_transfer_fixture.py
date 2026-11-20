#!/usr/bin/env python3
"""Generate public Google transfer examples without invoking any app codec."""

import argparse
import base64
import json
from pathlib import Path
from urllib.parse import quote

DESTINATION = Path(__file__).resolve().parents[1] / "spec/fixtures/google-authenticator-v1.json"
PUBLIC_SECRET = b"12345678901234567890"  # RFC 4226 test key.


def varint(value):
    value &= (1 << 64) - 1
    tail = bytearray()
    while value > 127:
        tail.append((value & 127) | 128)
        value >>= 7
    return bytes(tail) + bytes([value])


def integer(field, value):
    return varint(field * 8) + varint(value)


def length_delimited(field, data):
    return varint(field * 8 + 2) + varint(len(data)) + data


def account(name, issuer="", *, algorithm=None, digits=None, kind=None, counter=None):
    fields = length_delimited(1, PUBLIC_SECRET) + length_delimited(2, name.encode())
    if issuer:
        fields += length_delimited(3, issuer.encode())
    return fields + b"".join(integer(field, value) for field, value in
                             enumerate((algorithm, digits, kind, counter), 4) if value is not None)


def payload(entries, *, batch_id=21, size=1, index=0, version=1):
    return b"".join(length_delimited(1, entry) for entry in entries) + (
        integer(2, version) + integer(3, size) + integer(4, index) + integer(5, batch_id)
    )


def uri(data, *, url_safe=False):
    encoded = (base64.urlsafe_b64encode(data).decode().rstrip("=") if url_safe else
               base64.b64encode(data).decode())
    return "otpauth-migration://offline?data=" + quote(encoded, safe="")


def expected_entry(issuer, name, algorithm="SHA1", digits=6, counter=None):
    return {
        "issuer": issuer, "accountName": name,
        "secretBase64": base64.b64encode(PUBLIC_SECRET).decode(),
        "algorithm": algorithm, "digits": digits,
        "type": "TOTP" if counter is None else "HOTP",
        "period": 30 if counter is None else None, "counter": counter,
    }


def fixtures():
    normal = account("Public:alice@example.test", "Public", algorithm=1, digits=1, kind=2)
    hotp = account("计数器", "RFC", algorithm=1, digits=1, kind=1, counter=10300)
    defaults = account("Standalone")
    extended = account("示例:帳號", algorithm=2, digits=2, kind=2)
    return {"schemaVersion": 1, "cases": [
        {"name": "single", "uri": uri(payload([normal, hotp])), "expected": {
            "version": 1, "batchId": 21, "batchSize": 1, "batchIndex": 0,
            "entries": [expected_entry("Public", "alice@example.test"),
                        expected_entry("RFC", "计数器", counter=10300)],
        }},
        {"name": "second_page_urlsafe", "uri": uri(payload([extended], batch_id=-42, size=2, index=1),
                                                       url_safe=True), "expected": {
            "version": 1, "batchId": -42, "batchSize": 2, "batchIndex": 1,
            "entries": [expected_entry("示例", "帳號", "SHA256", 8)],
        }},
        {"name": "first_page_defaults", "uri": uri(payload([defaults], batch_id=-42, size=2)),
         "expected": {
             "version": 1, "batchId": -42, "batchSize": 2, "batchIndex": 0,
             "entries": [expected_entry(None, "Standalone")],
         }},
        {"name": "version_2_unique_id", "uri": uri(payload([
            account("Current", "Public") + length_delimited(8, b"synthetic-entry-id")
        ], version=2)), "expected": {
            "version": 2, "batchId": 21, "batchSize": 1, "batchIndex": 0,
            "entries": [expected_entry("Public", "Current")],
        }},
        *({"name": name, "uri": uri(raw), "expected": None} for name, raw in [
            ("truncated_varint", payload([normal]) + b"\x80"),
            ("wrong_version", payload([normal], version=99)),
            ("invalid_page_number", payload([normal], size=2, index=2)),
            ("too_many_pages", payload([normal], size=501)),
            ("no_accounts", payload([])),
            ("empty_name", payload([account("")])),
            ("unsupported_algorithm", payload([account("Public", algorithm=4)])),
            ("unsupported_digits", payload([account("Public", digits=3)])),
            ("negative_counter", payload([account("Public", kind=1, counter=-1)])),
            ("invalid_utf8", payload([length_delimited(1, PUBLIC_SECRET) + length_delimited(2, b"\xff")])),
        ]),
    ]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="regenerate the committed fixture")
    args = parser.parse_args()
    result = json.dumps(fixtures(), ensure_ascii=False, indent=2) + "\n"
    if args.write:
        DESTINATION.write_text(result, encoding="utf-8")
    elif DESTINATION.read_text(encoding="utf-8") != result:
        raise SystemExit("Google transfer fixture differs from the independent generator")
    else:
        print("Google transfer fixture matches the independent generator")


if __name__ == "__main__":
    main()
