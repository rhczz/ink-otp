#!/usr/bin/env python3
"""Cross-check the committed specs/fixtures against the independent Python implementation.

Checks the OTP and URI vectors and independently opens the public worked
example in spec/vault-format.md.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any, Callable
from unicodedata import normalize

from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import reference_totp as rt

class CrosscheckFailure(Exception):
    pass


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CrosscheckFailure(f"cannot read {path}: {exc}") from exc


# ---------------------------------------------------------------------------
# vectors.json
# ---------------------------------------------------------------------------


def check_vectors(spec_dir: Path) -> str:
    vectors = _read_json(spec_dir / "vectors.json")
    if not isinstance(vectors, dict) or vectors.get("version") != 1:
        raise CrosscheckFailure("vectors.json must be an object with version=1")
    if vectors.get("meta", {}).get("seed") != rt.RANDOM_SEED:
        raise CrosscheckFailure(
            f"vectors seed {vectors.get('meta', {}).get('seed')} != {rt.RANDOM_SEED}"
        )

    totp_vectors = vectors.get("totp")
    hotp_vectors = vectors.get("hotp")
    base32_vectors = vectors.get("base32")
    boundary_vectors = vectors.get("boundary")
    random_vectors = vectors.get("random_totp")
    for name, value in (
        ("totp", totp_vectors),
        ("hotp", hotp_vectors),
        ("base32", base32_vectors),
        ("boundary", boundary_vectors),
        ("random_totp", random_vectors),
    ):
        if not isinstance(value, list):
            raise CrosscheckFailure(f"vectors.{name} must be an array")

    if len(totp_vectors) != 18:
        raise CrosscheckFailure(f"expected 18 RFC 6238 TOTP vectors, got {len(totp_vectors)}")
    if len(hotp_vectors) != 10:
        raise CrosscheckFailure(f"expected 10 RFC 4226 HOTP vectors, got {len(hotp_vectors)}")
    if len(base32_vectors) < 20:
        raise CrosscheckFailure(f"expected >= 20 Base32 vectors, got {len(base32_vectors)}")
    if len(boundary_vectors) < 10:
        raise CrosscheckFailure(f"expected >= 10 boundary vectors, got {len(boundary_vectors)}")
    if len(random_vectors) < 50:
        raise CrosscheckFailure(f"expected >= 50 random TOTP vectors, got {len(random_vectors)}")

    seen_totp: set[tuple[int, str]] = set()
    for index, vector in enumerate(totp_vectors):
        secret = rt.b32decode(vector["secretBase32"], tolerant=True)
        if "secretAscii" in vector and secret != vector["secretAscii"].encode("ascii"):
            raise CrosscheckFailure(f"TOTP vector {index}: secretAscii does not match secretBase32")
        actual = rt.totp(
            secret,
            vector["time"],
            vector["digits"],
            vector["period"],
            vector["algorithm"],
        )
        if actual != vector["expected"]:
            raise CrosscheckFailure(
                f"TOTP vector {index} (t={vector['time']}, {vector['algorithm']}): "
                f"got {actual}, expected {vector['expected']}"
            )
        rfc_expected = rt.RFC6238_EXPECTED.get(vector["time"], {}).get(vector["algorithm"])
        if rfc_expected != vector["expected"]:
            raise CrosscheckFailure(f"TOTP vector {index} is not the RFC 6238 value")
        key = (vector["time"], vector["algorithm"])
        if key in seen_totp:
            raise CrosscheckFailure(f"duplicate TOTP vector {key}")
        seen_totp.add(key)
    expected_keys = {
        (time, algorithm) for time in rt.RFC6238_TIMES for algorithm in rt.ALGORITHMS
    }
    if seen_totp != expected_keys:
        raise CrosscheckFailure("TOTP vectors do not cover the full RFC 6238 table")

    seen_hotp: set[int] = set()
    for index, vector in enumerate(hotp_vectors):
        secret = rt.b32decode(vector["secretBase32"], tolerant=True)
        if "secretAscii" in vector and secret != vector["secretAscii"].encode("ascii"):
            raise CrosscheckFailure(f"HOTP vector {index}: secretAscii does not match secretBase32")
        actual = rt.hotp(secret, vector["counter"], vector["digits"], vector["algorithm"])
        if actual != vector["expected"]:
            raise CrosscheckFailure(f"HOTP vector {index} (counter={vector['counter']}): mismatch")
        if rt.RFC4226_EXPECTED[vector["counter"]] != vector["expected"]:
            raise CrosscheckFailure(f"HOTP vector {index} is not the RFC 4226 value")
        seen_hotp.add(vector["counter"])
    if seen_hotp != set(range(10)):
        raise CrosscheckFailure("HOTP vectors do not cover counters 0..9")

    for index, vector in enumerate(base32_vectors):
        if "expectedError" in vector:
            try:
                rt.b32decode(vector["input"], tolerant=True)
            except rt.OtpError as exc:
                if exc.code != vector["expectedError"]:
                    raise CrosscheckFailure(
                        f"Base32 vector {index}: got {exc.code}, expected {vector['expectedError']}"
                    )
            else:
                raise CrosscheckFailure(f"Base32 vector {index}: expected rejection")
        else:
            decoded = rt.b32decode(vector["input"], tolerant=True)
            if decoded.hex() != vector["expectedHex"]:
                raise CrosscheckFailure(f"Base32 vector {index}: wrong decoded bytes")
            if "expectedBase32" in vector and rt.b32encode(decoded) != vector["expectedBase32"]:
                raise CrosscheckFailure(f"Base32 vector {index}: wrong canonical encoding")

    for index, case in enumerate(boundary_vectors):
        try:
            rt.code_from_request(case["request"])
        except rt.OtpError as exc:
            if exc.code != case["expectedError"]:
                raise CrosscheckFailure(
                    f"boundary case {index} ({case.get('description')!r}): "
                    f"got {exc.code}, expected {case['expectedError']}"
                )
        else:
            raise CrosscheckFailure(f"boundary case {index}: expected rejection")

    algorithms: set[str] = set()
    digit_set: set[int] = set()
    periods: set[int] = set()
    lengths: set[int] = set()
    for index, vector in enumerate(random_vectors):
        secret = rt.b32decode(vector["secretBase32"], tolerant=True)
        lengths.add(len(secret))
        algorithms.add(vector["algorithm"])
        digit_set.add(vector["digits"])
        periods.add(vector["period"])
        actual = rt.totp(
            secret,
            vector["time"],
            vector["digits"],
            vector["period"],
            vector["algorithm"],
        )
        if actual != vector["expected"]:
            raise CrosscheckFailure(f"random TOTP vector {index}: mismatch")
    if algorithms != set(rt.ALGORITHMS):
        raise CrosscheckFailure(f"random algorithm coverage is {sorted(algorithms)}")
    if digit_set != set(rt.DIGITS_ALLOWED):
        raise CrosscheckFailure(f"random digits coverage is {sorted(digit_set)}")
    if periods != set(rt.RANDOM_PERIODS):
        raise CrosscheckFailure(f"random period coverage is {sorted(periods)}")
    if lengths != set(range(1, 65)):
        raise CrosscheckFailure(f"random secret lengths missing: {sorted(set(range(1, 65)) - lengths)}")

    return (
        f"{len(totp_vectors)} TOTP, {len(hotp_vectors)} HOTP, "
        f"{len(base32_vectors)} Base32, {len(boundary_vectors)} boundary, "
        f"{len(random_vectors)} random"
    )


# ---------------------------------------------------------------------------
# otpauth-cases.json
# ---------------------------------------------------------------------------


def check_otpauth_cases(spec_dir: Path) -> str:
    path = spec_dir / "otpauth-cases.json"
    cases = _read_json(path)
    if not isinstance(cases, list):
        raise CrosscheckFailure("otpauth-cases.json must be an array")
    seen: set[str] = set()
    for index, case in enumerate(cases):
        if not isinstance(case, dict):
            raise CrosscheckFailure(f"otpauth case {index} is not an object")
        name = case.get("name")
        if not isinstance(name, str) or not name:
            raise CrosscheckFailure(f"otpauth case {index} has no name")
        if name in seen:
            raise CrosscheckFailure(f"duplicate otpauth case name {name!r}")
        seen.add(name)
        if case.get("accept") is True:
            if case.get("error") is not None or not isinstance(case.get("expected"), dict):
                raise CrosscheckFailure(f"accepted case {name!r} has error/expected mismatch")
        elif case.get("accept") is False:
            if not isinstance(case.get("error"), str) or case.get("expected") is not None:
                raise CrosscheckFailure(f"rejected case {name!r} has error/expected mismatch")
        else:
            raise CrosscheckFailure(f"case {name!r} has non-boolean accept")
    return f"{len(cases)} parser cases ({sum(c['accept'] for c in cases)} accepted)"


def check_vault_example(spec_dir: Path) -> str:
    text = (spec_dir / "vault-format.md").read_text(encoding="utf-8")
    example = text.split("## 6. Worked example", 1)[1].split("## 7.", 1)[0]
    file_hex = re.search(r"(?m)^fileHex =\n([0-9a-f\n]+)```$", example)[1]
    data = bytes.fromhex(file_hex)
    expected_sha = re.search(r"(?m)^sha256 += +([0-9a-f]{64})$", example)
    if not expected_sha or hashlib.sha256(data).hexdigest() != expected_sha[1]:
        raise CrosscheckFailure("vault example SHA-256 differs from its specification")
    if data[:7] != b"2FAV\x01\x01\x01":
        raise CrosscheckFailure("vault example header is inconsistent")
    memory, iterations, lanes = (int.from_bytes(data[offset:offset + 4], "big")
                                 for offset in (7, 11, 15))
    if (memory, iterations, lanes) != (65_536, 3, 2):
        raise CrosscheckFailure("unexpected public vault KDF parameters")
    password = re.search(r'(?m)^password += "([^"]+)"$', example)[1]
    kek = hash_secret_raw(normalize("NFC", password).encode(), data[19:35],
                          iterations, memory, lanes, 32, Type.ID)
    expected_kek = re.search(r"(?m)^KEK += ([0-9a-f]{64})$", example)[1]
    if kek.hex() != expected_kek:
        raise CrosscheckFailure("vault Argon2id KEK differs from the documented example")
    dek = AESGCM(kek).decrypt(data[35:47], data[63:111], data[:63])
    plaintext = AESGCM(dek).decrypt(data[47:59], data[115:], data[:115])
    expected = re.search(r"(?m)^```json\n([^\n]+)\n```$", example)[1].encode()
    if plaintext != expected or len(plaintext) != int.from_bytes(data[111:115], "big"):
        raise CrosscheckFailure("vault AES-GCM plaintext differs from its specification")
    return f"{len(data)} bytes, independent Argon2id and two authenticated AES-GCM opens"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Cross-check InkOTP specs and fixtures")
    parser.add_argument("--spec", default="spec", help="spec directory (default: spec)")
    args = parser.parse_args(argv)

    spec_dir = Path(args.spec).expanduser()
    checks: list[tuple[str, Callable[[], str]]] = [
        ("vectors.json", lambda: check_vectors(spec_dir)),
        ("otpauth-cases.json", lambda: check_otpauth_cases(spec_dir)),
        ("vault-format.md", lambda: check_vault_example(spec_dir)),
    ]
    failures = 0
    for label, call in checks:
        try:
            detail = call()
        except CrosscheckFailure as exc:
            failures += 1
            print(f"[FAIL] {label}: {exc}")
        except Exception as exc:  # defensive: keep running the other checks
            failures += 1
            print(f"[FAIL] {label}: unexpected {type(exc).__name__}: {exc}")
        else:
            print(f"[PASS] {label}: {detail}")

    if failures:
        print(f"CROSSCHECK FAIL ({failures} failing section(s))")
        return 1
    print("CROSSCHECK PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
