#!/usr/bin/env python3
"""Independent reference implementation of the InkOTP backup format v1 (.2fab).

This tool is intentionally platform-neutral.  It implements the layout frozen
in ``spec/backup-format.md`` with argon2-cffi (Argon2id) and
cryptography (AES-256-GCM).  It never reads or writes Android Keystore data:
the backup contains only the password-wrapped payload.

Modes:
  --selftest              round-trip + tamper/limit tests
  --create-fixture        write spec/fixtures/backup-v1.{2fab,expected.json,meta.json}
  --decrypt FILE --password PW
  --inspect FILE
"""

from __future__ import annotations

import argparse
import hashlib
import json
import secrets
import struct
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from argon2.low_level import Type as Argon2Type
from argon2.low_level import hash_secret_raw
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_FIXTURES = ROOT / "spec" / "fixtures"

# ---------------------------------------------------------------------------
# Format constants (spec/backup-format.md)
# ---------------------------------------------------------------------------

MAGIC = b"2FAB"
FORMAT_VERSION = 1
KDF_ALGO_ARGON2ID = 1
HEADER_LEN = 50
SALT_LEN = 16
NONCE_LEN = 12
TAG_LEN = 16
KEY_LEN = 32

FILE_MAX_BYTES = 12 * 1024 * 1024
CT_MAX_BYTES = 8 * 1024 * 1024

MIN_MKIB = 8192
MAX_MKIB = 524288
MIN_TITER = 1
MAX_TITER = 10
MIN_PLANES = 1
MAX_PLANES = 8

DEFAULT_MKIB = 65536
DEFAULT_TITER = 3
DEFAULT_PLANES = 2

# Error codes; keep in sync with spec/backup-format.md and spec/README.md.
E_MAGIC = "BAK_E_MAGIC"
E_VERSION = "BAK_E_VERSION"
E_KDF_ALGO = "BAK_E_KDF_ALGO"
E_KDF_PARAMS = "BAK_E_KDF_PARAMS"
E_CT_LEN = "BAK_E_CT_LEN"
E_FILE_TRUNCATED = "BAK_E_FILE_TRUNCATED"
E_FILE_TOO_LARGE = "BAK_E_FILE_TOO_LARGE"
E_TRAILING_DATA = "BAK_E_TRAILING_DATA"
E_AUTH = "BAK_E_AUTH"
E_JSON = "BAK_E_JSON"
E_SCHEMA = "BAK_E_SCHEMA"

# Fixture is deliberately deterministic and uses a throwaway password.  Never
# reuse these salts/nonces for a real backup.
FIXTURE_PASSWORD = "correct horse battery staple"
FIXTURE_SALT = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
FIXTURE_NONCE = bytes.fromhex("101112131415161718191a1b")
FIXTURE_VAULT: dict[str, Any] = {
    "schemaVersion": 1,
    "vaultId": "6f1c2a6e-6f4d-4c1b-9d4e-2a7f0f3f9b21",
    "createdAt": "2026-11-04T10:00:00Z",
    "updatedAt": "2026-11-04T10:05:12Z",
    "settings": {
        "lockTimeoutSeconds": 30,
        "hideCodesByDefault": False,
        "clipboardClearSeconds": 30,
    },
    "items": [
        {
            "id": "0d9a3c1e-8f21-4d6a-9c3e-1234567890ab",
            "type": "totp",
            "issuer": "GitHub",
            "accountName": "me@example.com",
            "secretB64": "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
            "algorithm": "SHA1",
            "digits": 6,
            "period": 30,
            "counter": None,
            "tags": [],
            "icon": {"kind": "letter", "value": "G", "colorHex": "#24292F"},
            "sortIndex": 1000,
            "origin": "qr",
            "createdAt": "2026-11-04T10:03:00Z",
            "updatedAt": "2026-11-04T10:03:00Z",
        },
        {
            "id": "7c4b2a10-3d5e-4f6a-8b9c-0d1e2f3a4b5c",
            "type": "totp",
            "issuer": "Example Corp",
            "accountName": "alice@example.com",
            "secretB64": "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
            "algorithm": "SHA256",
            "digits": 8,
            "period": 60,
            "counter": None,
            "tags": [],
            "icon": {"kind": "letter", "value": "E", "colorHex": "#1F6FEB"},
            "sortIndex": 2000,
            "origin": "manual",
            "createdAt": "2026-11-04T10:04:00Z",
            "updatedAt": "2026-11-04T10:04:00Z",
        },
    ],
}


class BackupError(Exception):
    """Machine-readable format error (``code`` is stable API)."""

    def __init__(self, code: str, message: str = "") -> None:
        self.code = code
        self.message = message
        super().__init__(f"{code}: {message}" if message else code)


# ---------------------------------------------------------------------------
# Header
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Header:
    format_version: int
    kdf_algo: int
    m_kib: int
    t_iter: int
    p_lanes: int
    salt: bytes
    nonce: bytes
    ct_len: int

    @property
    def total_len(self) -> int:
        return HEADER_LEN + self.ct_len + TAG_LEN


def _validate_params(m_kib: Any, t_iter: Any, p_lanes: Any) -> tuple[int, int, int]:
    values: list[int] = []
    for name, value in (("mKiB", m_kib), ("tIter", t_iter), ("pLanes", p_lanes)):
        if isinstance(value, bool) or not isinstance(value, int):
            raise BackupError(E_KDF_PARAMS, f"{name} must be an integer")
        values.append(value)
    m, t, p = values
    if not MIN_MKIB <= m <= MAX_MKIB:
        raise BackupError(E_KDF_PARAMS, f"mKiB must be {MIN_MKIB}..{MAX_MKIB}, got {m}")
    if not MIN_TITER <= t <= MAX_TITER:
        raise BackupError(E_KDF_PARAMS, f"tIter must be {MIN_TITER}..{MAX_TITER}, got {t}")
    if not MIN_PLANES <= p <= MAX_PLANES:
        raise BackupError(E_KDF_PARAMS, f"pLanes must be {MIN_PLANES}..{MAX_PLANES}, got {p}")
    if m < 8 * p:
        raise BackupError(E_KDF_PARAMS, "Argon2 requires mKiB >= 8 * pLanes")
    return m, t, p


def parse_header(data: bytes) -> Header:
    """Validate the fixed header and the exact file length, without decrypting."""
    if not isinstance(data, (bytes, bytearray, memoryview)):
        raise BackupError(E_FILE_TRUNCATED, "input is not bytes")
    raw = bytes(data)
    if len(raw) > FILE_MAX_BYTES:
        raise BackupError(E_FILE_TOO_LARGE, f"file exceeds {FILE_MAX_BYTES} bytes")
    if len(raw) < HEADER_LEN:
        raise BackupError(E_FILE_TRUNCATED, f"file is shorter than the {HEADER_LEN}-byte header")
    if raw[0:4] != MAGIC:
        raise BackupError(E_MAGIC, "expected ASCII magic '2FAB'")
    format_version = raw[4]
    if format_version != FORMAT_VERSION:
        raise BackupError(E_VERSION, f"unsupported formatVersion {format_version}")
    kdf_algo = raw[5]
    if kdf_algo != KDF_ALGO_ARGON2ID:
        raise BackupError(E_KDF_ALGO, f"unsupported kdfAlgo {kdf_algo}")
    m_kib, t_iter, p_lanes = struct.unpack(">III", raw[6:18])
    _validate_params(m_kib, t_iter, p_lanes)
    salt = raw[18:34]
    nonce = raw[34:46]
    ct_len = int.from_bytes(raw[46:50], "big")
    if ct_len == 0:
        raise BackupError(E_CT_LEN, "ctLen must be greater than zero")
    if ct_len > CT_MAX_BYTES:
        raise BackupError(E_CT_LEN, f"ctLen exceeds {CT_MAX_BYTES} bytes")
    expected_len = HEADER_LEN + ct_len + TAG_LEN
    if len(raw) < expected_len:
        raise BackupError(
            E_FILE_TRUNCATED,
            f"declared ctLen {ct_len} needs {expected_len} bytes, file has {len(raw)}",
        )
    if len(raw) > expected_len:
        raise BackupError(E_TRAILING_DATA, f"{len(raw) - expected_len} trailing bytes")
    return Header(
        format_version=format_version,
        kdf_algo=kdf_algo,
        m_kib=m_kib,
        t_iter=t_iter,
        p_lanes=p_lanes,
        salt=salt,
        nonce=nonce,
        ct_len=ct_len,
    )


# ---------------------------------------------------------------------------
# KDF + AEAD
# ---------------------------------------------------------------------------


def derive_kek(
    password: str,
    salt: bytes,
    m_kib: int = DEFAULT_MKIB,
    t_iter: int = DEFAULT_TITER,
    p_lanes: int = DEFAULT_PLANES,
) -> bytes:
    """Argon2id v1.3 -> 32-byte KEK.  Password is NFC-normalized UTF-8."""
    if not isinstance(password, str):
        raise BackupError(E_AUTH, "password must be a string")
    if len(salt) != SALT_LEN:
        raise BackupError(E_KDF_PARAMS, f"salt must be {SALT_LEN} bytes")
    m, t, p = _validate_params(m_kib, t_iter, p_lanes)
    password_bytes = unicodedata.normalize("NFC", password).encode("utf-8")
    try:
        return hash_secret_raw(
            secret=password_bytes,
            salt=salt,
            time_cost=t,
            memory_cost=m,
            parallelism=p,
            hash_len=KEY_LEN,
            type=Argon2Type.ID,
            version=19,  # Argon2 v1.3
        )
    except Exception as exc:  # argon2-cffi raises several low-level errors
        raise BackupError(E_KDF_PARAMS, f"Argon2id failed: {exc}") from exc


def _build_header(
    *,
    m_kib: int,
    t_iter: int,
    p_lanes: int,
    salt: bytes,
    nonce: bytes,
    ct_len: int,
) -> bytes:
    if len(salt) != SALT_LEN:
        raise BackupError(E_KDF_PARAMS, f"salt must be {SALT_LEN} bytes")
    if len(nonce) != NONCE_LEN:
        raise BackupError(E_KDF_PARAMS, f"nonce must be {NONCE_LEN} bytes")
    if not 0 < ct_len <= CT_MAX_BYTES:
        raise BackupError(E_CT_LEN, "ciphertext length out of range")
    return struct.pack(
        ">4sBBIII16s12sI",
        MAGIC,
        FORMAT_VERSION,
        KDF_ALGO_ARGON2ID,
        m_kib,
        t_iter,
        p_lanes,
        salt,
        nonce,
        ct_len,
    )


def build_backup(
    vault_json: bytes,
    password: str,
    *,
    m_kib: int = DEFAULT_MKIB,
    t_iter: int = DEFAULT_TITER,
    p_lanes: int = DEFAULT_PLANES,
    salt: bytes | None = None,
    nonce: bytes | None = None,
) -> bytes:
    """Encrypt ``vault_json`` directly with the password-derived KEK (no DEK)."""
    if not isinstance(vault_json, (bytes, bytearray)):
        raise BackupError(E_JSON, "vault_json must be bytes")
    plaintext = bytes(vault_json)
    if not 0 < len(plaintext) <= CT_MAX_BYTES:
        raise BackupError(E_CT_LEN, "vault JSON length out of range")
    m, t, p = _validate_params(m_kib, t_iter, p_lanes)
    salt = secrets.token_bytes(SALT_LEN) if salt is None else salt
    nonce = secrets.token_bytes(NONCE_LEN) if nonce is None else nonce
    header = _build_header(
        m_kib=m,
        t_iter=t,
        p_lanes=p,
        salt=salt,
        nonce=nonce,
        ct_len=len(plaintext),
    )
    kek = derive_kek(password, salt, m, t, p)
    blob = AESGCM(kek).encrypt(nonce, plaintext, header)
    ciphertext, tag = blob[:-TAG_LEN], blob[-TAG_LEN:]
    return header + ciphertext + tag


def decrypt_backup(data: bytes, password: str) -> bytes:
    """Validate header, derive KEK, verify GCM tag, then return plaintext bytes."""
    header = parse_header(data)
    raw = bytes(data)
    header_bytes = raw[:HEADER_LEN]
    ciphertext = raw[HEADER_LEN : HEADER_LEN + header.ct_len]
    tag = raw[HEADER_LEN + header.ct_len :]
    kek = derive_kek(password, header.salt, header.m_kib, header.t_iter, header.p_lanes)
    try:
        return AESGCM(kek).decrypt(header.nonce, ciphertext + tag, header_bytes)
    except InvalidTag as exc:
        # Wrong password, corrupt ciphertext/tag, or tampered header all
        # deliberately collapse to the same error.
        raise BackupError(E_AUTH, "authentication failed (wrong password or corrupt file)") from exc


def parse_vault_json(plaintext: bytes) -> dict[str, Any]:
    try:
        value = json.loads(plaintext.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BackupError(E_JSON, "decrypted payload is not valid UTF-8 JSON") from exc
    if not isinstance(value, dict):
        raise BackupError(E_SCHEMA, "vault JSON must be an object")
    if value.get("schemaVersion") != 1:
        raise BackupError(E_SCHEMA, "unsupported vault schemaVersion")
    if not isinstance(value.get("items"), list):
        raise BackupError(E_SCHEMA, "vault JSON must contain an items array")
    return value


def decrypt_to_json(data: bytes, password: str) -> dict[str, Any]:
    return parse_vault_json(decrypt_backup(data, password))


# ---------------------------------------------------------------------------
# Fixture
# ---------------------------------------------------------------------------


def fixture_vault_json() -> bytes:
    return (json.dumps(FIXTURE_VAULT, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def create_fixture(fixtures_dir: Path) -> dict[str, Any]:
    fixtures_dir.mkdir(parents=True, exist_ok=True)
    plaintext = fixture_vault_json()
    data = build_backup(
        plaintext,
        FIXTURE_PASSWORD,
        m_kib=DEFAULT_MKIB,
        t_iter=DEFAULT_TITER,
        p_lanes=DEFAULT_PLANES,
        salt=FIXTURE_SALT,
        nonce=FIXTURE_NONCE,
    )
    header = parse_header(data)
    # Fail closed if the fixture cannot be read back.
    if decrypt_backup(data, FIXTURE_PASSWORD) != plaintext:
        raise BackupError(E_AUTH, "fixture round-trip failed")
    if parse_vault_json(plaintext) != FIXTURE_VAULT:
        raise BackupError(E_SCHEMA, "fixture vault JSON does not round-trip")

    backup_path = fixtures_dir / "backup-v1.2fab"
    expected_path = fixtures_dir / "backup-v1.expected.json"
    meta_path = fixtures_dir / "backup-v1.meta.json"
    backup_path.write_bytes(data)
    expected_path.write_bytes(plaintext)

    tag = data[-TAG_LEN:]
    meta: dict[str, Any] = {
        "format": "2fab",
        "formatVersion": FORMAT_VERSION,
        "password": FIXTURE_PASSWORD,
        "kdf": {
            "algorithm": "Argon2id",
            "version": 19,
            "mKiB": DEFAULT_MKIB,
            "tIter": DEFAULT_TITER,
            "pLanes": DEFAULT_PLANES,
            "saltHex": FIXTURE_SALT.hex(),
            "outputBytes": KEY_LEN,
        },
        "nonceHex": FIXTURE_NONCE.hex(),
        "ctLen": header.ct_len,
        "tagHex": tag.hex(),
        "file": backup_path.name,
        "fileBytes": len(data),
        "fileSha256": hashlib.sha256(data).hexdigest(),
        "expected": expected_path.name,
        "plaintextSha256": hashlib.sha256(plaintext).hexdigest(),
    }
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return meta


# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------


SELFTEST_PASSWORD = "correct horse battery staple"
SELFTEST_SALT = bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
SELFTEST_NONCE = bytes.fromhex("b0b1b2b3b4b5b6b7b8b9babb")
SELFTEST_JSON = b'{"schemaVersion":1,"items":[]}'


def _expect_error(code: str, call: Any) -> None:
    try:
        call()
    except BackupError as exc:
        if exc.code != code:
            raise AssertionError(f"expected {code}, got {exc.code} ({exc.message})") from exc
    else:
        raise AssertionError(f"expected {code}, call succeeded")


def _with_byte(data: bytes, offset: int, value: int) -> bytes:
    mutable = bytearray(data)
    mutable[offset] = value
    return bytes(mutable)


def selftest() -> int:
    checks = 0

    def ok(label: str) -> None:
        nonlocal checks
        checks += 1
        print(f"[ok] {label}")

    data = build_backup(
        SELFTEST_JSON,
        SELFTEST_PASSWORD,
        salt=SELFTEST_SALT,
        nonce=SELFTEST_NONCE,
    )
    header = parse_header(data)
    assert header.m_kib == DEFAULT_MKIB
    assert header.t_iter == DEFAULT_TITER
    assert header.p_lanes == DEFAULT_PLANES
    assert decrypt_backup(data, SELFTEST_PASSWORD) == SELFTEST_JSON
    ok("round-trip AES-256-GCM / Argon2id default parameters")

    fast = build_backup(
        SELFTEST_JSON,
        SELFTEST_PASSWORD,
        m_kib=MIN_MKIB,
        t_iter=1,
        p_lanes=1,
        salt=SELFTEST_SALT,
        nonce=SELFTEST_NONCE,
    )
    assert decrypt_backup(fast, SELFTEST_PASSWORD) == SELFTEST_JSON
    ok("round-trip lower bound KDF parameters (8192/1/1)")

    _expect_error(E_MAGIC, lambda: parse_header(_with_byte(data, 0, ord("X"))))
    ok("tamper: magic rejected")
    _expect_error(E_VERSION, lambda: parse_header(_with_byte(data, 4, 2)))
    ok("tamper: formatVersion rejected")
    _expect_error(E_KDF_ALGO, lambda: parse_header(_with_byte(data, 5, 2)))
    ok("tamper: kdfAlgo rejected")

    def set_m(value: int) -> None:
        mutated = bytearray(data)
        mutated[6:10] = value.to_bytes(4, "big")
        parse_header(bytes(mutated))

    def set_t(value: int) -> None:
        mutated = bytearray(data)
        mutated[10:14] = value.to_bytes(4, "big")
        parse_header(bytes(mutated))

    def set_p(value: int) -> None:
        mutated = bytearray(data)
        mutated[14:18] = value.to_bytes(4, "big")
        parse_header(bytes(mutated))

    _expect_error(E_KDF_PARAMS, lambda: set_m(MAX_MKIB + 1))
    _expect_error(E_KDF_PARAMS, lambda: set_m(0))
    _expect_error(E_KDF_PARAMS, lambda: set_t(0))
    _expect_error(E_KDF_PARAMS, lambda: set_p(0))
    _expect_error(E_KDF_PARAMS, lambda: set_p(MAX_PLANES + 1))
    ok("tamper: KDF parameter limits rejected")

    def set_ct_len(value: int) -> None:
        mutated = bytearray(data)
        mutated[46:50] = value.to_bytes(4, "big")
        parse_header(bytes(mutated))

    _expect_error(E_CT_LEN, lambda: set_ct_len(0))
    _expect_error(E_CT_LEN, lambda: set_ct_len(CT_MAX_BYTES + 1))
    ok("tamper: ctLen limits rejected")

    _expect_error(E_FILE_TRUNCATED, lambda: parse_header(data[: HEADER_LEN - 1]))
    _expect_error(E_FILE_TRUNCATED, lambda: parse_header(data[:-1]))
    _expect_error(E_TRAILING_DATA, lambda: parse_header(data + b"\x00"))
    _expect_error(E_FILE_TOO_LARGE, lambda: parse_header(b"2FAB" + b"\x00" * FILE_MAX_BYTES))
    ok("tamper: truncation, trailing bytes and oversize file rejected")

    _expect_error(E_AUTH, lambda: decrypt_backup(data, "wrong password"))
    ok("wrong password rejected")
    _expect_error(E_AUTH, lambda: decrypt_backup(_with_byte(data, HEADER_LEN, data[HEADER_LEN] ^ 1), SELFTEST_PASSWORD))
    ok("tamper: ciphertext byte rejected")
    _expect_error(E_AUTH, lambda: decrypt_backup(_with_byte(data, len(data) - 1, data[-1] ^ 1), SELFTEST_PASSWORD))
    ok("tamper: authentication tag rejected")
    _expect_error(E_AUTH, lambda: decrypt_backup(_with_byte(fast, 18, fast[18] ^ 1), SELFTEST_PASSWORD))
    ok("tamper: salt changes KEK -> auth failure")
    _expect_error(E_AUTH, lambda: decrypt_backup(_with_byte(fast, 34, fast[34] ^ 1), SELFTEST_PASSWORD))
    ok("tamper: nonce -> auth failure")

    invalid_json = build_backup(
        b"not json",
        SELFTEST_PASSWORD,
        m_kib=MIN_MKIB,
        t_iter=1,
        p_lanes=1,
        salt=SELFTEST_SALT,
        nonce=SELFTEST_NONCE,
    )
    _expect_error(E_JSON, lambda: decrypt_to_json(invalid_json, SELFTEST_PASSWORD))
    ok("decrypted non-JSON rejected")

    unsupported_schema = build_backup(
        b'{"schemaVersion":2,"items":[]}',
        SELFTEST_PASSWORD,
        m_kib=MIN_MKIB,
        t_iter=1,
        p_lanes=1,
        salt=SELFTEST_SALT,
        nonce=SELFTEST_NONCE,
    )
    _expect_error(E_SCHEMA, lambda: decrypt_to_json(unsupported_schema, SELFTEST_PASSWORD))
    ok("unsupported schemaVersion rejected")

    print(f"SELFTEST PASS ({checks}/{checks})")
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _print_error(exc: Exception) -> int:
    if isinstance(exc, BackupError):
        print(f"{exc.code}: {exc.message}", file=sys.stderr)
    else:
        print(f"error: {exc}", file=sys.stderr)
    return 2


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="InkOTP .2fab reference implementation")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--selftest", action="store_true", help="round-trip and tamper tests")
    mode.add_argument("--create-fixture", action="store_true", help="write the committed deterministic fixture")
    mode.add_argument("--decrypt", metavar="FILE", help="print the decrypted vault JSON")
    mode.add_argument("--inspect", metavar="FILE", help="print header fields without decrypting")
    parser.add_argument("--password", help="password for --decrypt")
    parser.add_argument("--fixtures-dir", default=str(DEFAULT_FIXTURES), help="target for --create-fixture")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()
    if args.create_fixture:
        try:
            meta = create_fixture(Path(args.fixtures_dir))
        except (BackupError, OSError) as exc:
            return _print_error(exc)
        print(
            f"wrote backup-v1.2fab ({meta['fileBytes']} bytes, sha256 {meta['fileSha256']})"
        )
        print(f"wrote backup-v1.expected.json ({meta['plaintextSha256']})")
        print(f"wrote backup-v1.meta.json (password: {FIXTURE_PASSWORD!r})")
        return 0
    if args.decrypt:
        if args.password is None:
            parser.error("--decrypt requires --password")
        try:
            data = Path(args.decrypt).read_bytes()
            plaintext = decrypt_backup(data, args.password)
            sys.stdout.write(plaintext.decode("utf-8"))
        except (BackupError, OSError, UnicodeDecodeError) as exc:
            return _print_error(exc)
        return 0
    if args.inspect:
        try:
            data = Path(args.inspect).read_bytes()
            header = parse_header(data)
        except (BackupError, OSError) as exc:
            return _print_error(exc)
        print(f"file:          {args.inspect}")
        print(f"size:          {len(data)} bytes")
        print(f"magic:         {MAGIC.decode('ascii')}")
        print(f"formatVersion: {header.format_version}")
        print(f"kdfAlgo:       {header.kdf_algo} (1 = Argon2id v1.3)")
        print(f"mKiB:          {header.m_kib}")
        print(f"tIter:         {header.t_iter}")
        print(f"pLanes:        {header.p_lanes}")
        print(f"salt:          {header.salt.hex()}")
        print(f"nonce:         {header.nonce.hex()}")
        print(f"ctLen:         {header.ct_len}")
        print(f"tagLen:        {TAG_LEN}")
        return 0

    parser.print_help()
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
