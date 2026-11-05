#!/usr/bin/env python3
"""Cross-check the committed specs/fixtures against the independent Python implementation.

Checks:
  * spec/vectors.json: RFC 4226/6238/4648 values, boundary behaviour, random coverage
  * spec/fixtures/backup-v1.2fab: header, hashes, password decrypt, expected JSON
  * spec/otpauth-cases.json: structural sanity (case names, accept/error shape)
  * <backup-dir>/kotlin-backup.2fab (optional): decrypt with the documented
    fixture password (or a password in kotlin-backup.meta.json) and compare
    with kotlin-backup.expected.json

Exit code 0 = PASS, 1 = verification failure.  A missing Kotlin artifact is a
skip, not a failure.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Callable

TOOLS_DIR = Path(__file__).resolve().parent
ROOT = TOOLS_DIR.parent
sys.path.insert(0, str(TOOLS_DIR))

import reference_backup as rb  # noqa: E402
import reference_totp as rt  # noqa: E402

FIXTURE_PASSWORD = rb.FIXTURE_PASSWORD
DEFAULT_PASSWORD = FIXTURE_PASSWORD


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


# ---------------------------------------------------------------------------
# backup fixture
# ---------------------------------------------------------------------------


def check_fixture(spec_dir: Path) -> str:
    fixtures = spec_dir / "fixtures"
    backup_path = fixtures / "backup-v1.2fab"
    expected_path = fixtures / "backup-v1.expected.json"
    meta_path = fixtures / "backup-v1.meta.json"
    for path in (backup_path, expected_path, meta_path):
        if not path.is_file():
            raise CrosscheckFailure(f"missing fixture file: {path}")

    data = backup_path.read_bytes()
    if len(data) >= 8 * 1024:
        raise CrosscheckFailure(f"fixture is {len(data)} bytes; expected < 8 KiB")
    meta = _read_json(meta_path)
    if meta.get("format") != "2fab":
        raise CrosscheckFailure("fixture meta format is not 2fab")
    if meta.get("formatVersion") != 1:
        raise CrosscheckFailure("fixture meta formatVersion is not 1")
    if meta.get("fileBytes") != len(data):
        raise CrosscheckFailure("fixture meta fileBytes does not match the file")
    if hashlib.sha256(data).hexdigest() != meta.get("fileSha256"):
        raise CrosscheckFailure("fixture fileSha256 does not match meta")
    if meta.get("tagHex") != data[-16:].hex():
        raise CrosscheckFailure("fixture meta tagHex does not match the file")
    if meta.get("password") != FIXTURE_PASSWORD:
        raise CrosscheckFailure("fixture password does not match the documented password")
    if meta.get("expected") != "backup-v1.expected.json":
        raise CrosscheckFailure("fixture meta expected filename is wrong")

    header = rb.parse_header(data)
    if (header.m_kib, header.t_iter, header.p_lanes) != (rb.DEFAULT_MKIB, rb.DEFAULT_TITER, rb.DEFAULT_PLANES):
        raise CrosscheckFailure("fixture KDF parameters are not 65536/3/2")
    kdf_meta = meta.get("kdf", {})
    if kdf_meta.get("mKiB") != header.m_kib or kdf_meta.get("tIter") != header.t_iter:
        raise CrosscheckFailure("fixture meta KDF parameters disagree with the file")
    if kdf_meta.get("pLanes") != header.p_lanes:
        raise CrosscheckFailure("fixture meta pLanes disagrees with the file")
    if kdf_meta.get("algorithm") != "Argon2id" or kdf_meta.get("version") != 19:
        raise CrosscheckFailure("fixture meta KDF algorithm/version is wrong")
    if kdf_meta.get("outputBytes") != 32:
        raise CrosscheckFailure("fixture meta KDF outputBytes is not 32")
    if header.salt.hex() != kdf_meta.get("saltHex"):
        raise CrosscheckFailure("fixture meta salt disagrees with the file")
    if header.nonce.hex() != meta.get("nonceHex"):
        raise CrosscheckFailure("fixture meta nonce disagrees with the file")
    if header.ct_len != meta.get("ctLen"):
        raise CrosscheckFailure("fixture meta ctLen disagrees with the file")

    expected_bytes = expected_path.read_bytes()
    if hashlib.sha256(expected_bytes).hexdigest() != meta.get("plaintextSha256"):
        raise CrosscheckFailure("expected plaintext hash does not match meta")
    decrypted = rb.decrypt_backup(data, FIXTURE_PASSWORD)
    if decrypted != expected_bytes:
        raise CrosscheckFailure("decrypted fixture does not match backup-v1.expected.json byte-for-byte")
    parsed = rb.parse_vault_json(decrypted)
    expected_parsed = _read_json(expected_path)
    if parsed != expected_parsed:
        raise CrosscheckFailure("decrypted fixture JSON does not match expected JSON")
    items = parsed.get("items", [])
    if len(items) != 2:
        raise CrosscheckFailure(f"fixture should contain 2 items, got {len(items)}")
    profiles = {(item.get("algorithm"), item.get("digits"), item.get("period")) for item in items}
    if profiles != {("SHA1", 6, 30), ("SHA256", 8, 60)}:
        raise CrosscheckFailure(f"fixture item profiles are {sorted(profiles)}")

    return (
        f"{len(data)} bytes, {len(items)} items, "
        f"sha256 {hashlib.sha256(data).hexdigest()[:16]}..."
    )


# ---------------------------------------------------------------------------
# optional Kotlin-produced backup
# ---------------------------------------------------------------------------


def check_kotlin_backup(backup_dir: Path) -> str:
    if not backup_dir.is_dir():
        return f"backup dir {backup_dir} not found (skipped)"
    kotlin_path = backup_dir / "kotlin-backup.2fab"
    if not kotlin_path.is_file():
        return f"{kotlin_path} not found (skipped)"

    expected_path = backup_dir / "kotlin-backup.expected.json"
    if not expected_path.is_file():
        raise CrosscheckFailure(f"{kotlin_path.name} exists but {expected_path.name} is missing")
    meta_path = backup_dir / "kotlin-backup.meta.json"
    password = DEFAULT_PASSWORD
    if meta_path.is_file():
        meta = _read_json(meta_path)
        if isinstance(meta.get("password"), str):
            password = meta["password"]

    data = kotlin_path.read_bytes()
    decrypted = rb.decrypt_backup(data, password)
    actual = rb.parse_vault_json(decrypted)
    expected = _read_json(expected_path)
    if actual != expected:
        raise CrosscheckFailure("Kotlin backup JSON does not match kotlin-backup.expected.json")
    return f"{len(data)} bytes decrypted with documented password, JSON matches"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Cross-check InkOTP specs and fixtures")
    parser.add_argument("--spec", default="spec", help="spec directory (default: spec)")
    parser.add_argument(
        "--backup-dir",
        default=str(ROOT / "android" / "core" / "build" / "crosscheck"),
        help="directory containing optional kotlin-backup.2fab",
    )
    args = parser.parse_args(argv)

    spec_dir = Path(args.spec).expanduser()
    backup_dir = Path(args.backup_dir).expanduser()
    checks: list[tuple[str, Callable[[], str]]] = [
        ("vectors.json", lambda: check_vectors(spec_dir)),
        ("otpauth-cases.json", lambda: check_otpauth_cases(spec_dir)),
        ("fixture backup-v1.2fab", lambda: check_fixture(spec_dir)),
        ("Kotlin backup", lambda: check_kotlin_backup(backup_dir)),
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
