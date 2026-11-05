#!/usr/bin/env python3
"""Independent OTP reference implementation for InkOTP.

This file is the source of truth for ``spec/vectors.json``.  It uses only the
Python standard library and is deliberately written without reference to the
Android/Kotlin implementation: if the two disagree, the RFC vectors in this
tool win.

Supported:
  * RFC 4648 Base32 (strict and tolerant decoding, canonical encoding)
  * RFC 4226 HOTP (u64 big-endian counter)
  * RFC 6238 TOTP (floor(unixSeconds / period))
  * RFC 6238 Appendix B / RFC 4226 Appendix D self-test
  * deterministic generation and verification of ``spec/vectors.json``

Usage:
  python tools/reference_totp.py --selftest
  python tools/reference_totp.py --generate-vectors spec/vectors.json
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import random
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_VECTORS = ROOT / "spec" / "vectors.json"

# ---------------------------------------------------------------------------
# Domain limits (mirrored by spec/README.md and the platform implementations)
# ---------------------------------------------------------------------------

SECRET_MIN_BYTES = 1
SECRET_MAX_BYTES = 255
DIGITS_ALLOWED = (6, 8)
PERIOD_MIN = 1
PERIOD_MAX = 300
U64_MAX = (1 << 64) - 1
# TOTP time is capped at i64 max so counter = time // period always fits a
# signed 64-bit value on every platform (Java/Kotlin `Long`).
TOTP_TIME_MAX = (1 << 63) - 1
ALGORITHMS = ("SHA1", "SHA256", "SHA512")
HASH_BY_ALGORITHM = {
    "SHA1": hashlib.sha1,
    "SHA256": hashlib.sha256,
    "SHA512": hashlib.sha512,
}

BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
BASE32_VALUE = {char: index for index, char in enumerate(BASE32_ALPHABET)}
BASE32_TOLERANT_IGNORED = " \t\r\n\v\f-"
# Encoded data lengths (without padding) that can represent whole bytes.
BASE32_VALID_DATA_LENGTHS_MOD_8 = frozenset((0, 2, 4, 5, 7))


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------


class OtpError(Exception):
    """Machine-readable domain error (``code`` is stable API)."""

    def __init__(self, code: str, message: str = "") -> None:
        self.code = code
        self.message = message
        super().__init__(f"{code}: {message}" if message else code)


# Error codes used by this module; keep in sync with spec/README.md.
E_SECRET_EMPTY = "SECRET_EMPTY"
E_SECRET_TOO_LONG = "SECRET_TOO_LONG"
E_DIGITS_UNSUPPORTED = "DIGITS_UNSUPPORTED"
E_PERIOD_OUT_OF_RANGE = "PERIOD_OUT_OF_RANGE"
E_ALGORITHM_UNSUPPORTED = "ALGORITHM_UNSUPPORTED"
E_COUNTER_NEGATIVE = "COUNTER_NEGATIVE"
E_COUNTER_OVERFLOW = "COUNTER_OVERFLOW"
E_TIME_OUT_OF_RANGE = "TIME_OUT_OF_RANGE"
E_KIND_UNSUPPORTED = "KIND_UNSUPPORTED"
E_REQUEST_INVALID = "REQUEST_INVALID"
E_SECRET_HEX_INVALID = "SECRET_HEX_INVALID"

E_BASE32_INVALID_CHAR = "BASE32_INVALID_CHAR"
E_BASE32_INVALID_LENGTH = "BASE32_INVALID_LENGTH"
E_BASE32_INVALID_PADDING = "BASE32_INVALID_PADDING"
E_BASE32_INVALID_TRAILING_BITS = "BASE32_INVALID_TRAILING_BITS"


def _require_int(value: Any, code: str, field: str) -> int:
    # bool is an int subclass; reject it explicitly.
    if isinstance(value, bool) or not isinstance(value, int):
        raise OtpError(code, f"{field} must be an integer")
    return value


def validate_algorithm(algorithm: Any) -> str:
    """Return canonical upper-case algorithm name or raise."""
    if not isinstance(algorithm, str):
        raise OtpError(E_ALGORITHM_UNSUPPORTED, "algorithm must be a string")
    normalized = algorithm.upper()
    if normalized not in HASH_BY_ALGORITHM:
        raise OtpError(E_ALGORITHM_UNSUPPORTED, f"unsupported algorithm: {algorithm!r}")
    return normalized


def validate_secret(secret: Any) -> bytes:
    if not isinstance(secret, (bytes, bytearray, memoryview)):
        raise OtpError(E_SECRET_EMPTY, "secret must be raw bytes")
    raw = bytes(secret)
    if len(raw) < SECRET_MIN_BYTES:
        raise OtpError(E_SECRET_EMPTY, "secret must contain at least one byte")
    if len(raw) > SECRET_MAX_BYTES:
        raise OtpError(
            E_SECRET_TOO_LONG,
            f"secret is {len(raw)} bytes; maximum is {SECRET_MAX_BYTES}",
        )
    return raw


def validate_digits(digits: Any) -> int:
    value = _require_int(digits, E_DIGITS_UNSUPPORTED, "digits")
    if value not in DIGITS_ALLOWED:
        raise OtpError(E_DIGITS_UNSUPPORTED, f"unsupported digits: {value}")
    return value


def validate_period(period: Any) -> int:
    value = _require_int(period, E_PERIOD_OUT_OF_RANGE, "period")
    if not PERIOD_MIN <= value <= PERIOD_MAX:
        raise OtpError(
            E_PERIOD_OUT_OF_RANGE,
            f"period must be {PERIOD_MIN}..{PERIOD_MAX} seconds, got {value}",
        )
    return value


def validate_counter(counter: Any) -> int:
    value = _require_int(counter, E_COUNTER_NEGATIVE, "counter")
    if value < 0:
        raise OtpError(E_COUNTER_NEGATIVE, "counter must be unsigned")
    if value > U64_MAX:
        raise OtpError(E_COUNTER_OVERFLOW, "counter exceeds u64 range")
    return value


def validate_time(unix_seconds: Any) -> int:
    value = _require_int(unix_seconds, E_TIME_OUT_OF_RANGE, "time")
    if value < 0 or value > TOTP_TIME_MAX:
        raise OtpError(
            E_TIME_OUT_OF_RANGE,
            f"time must be 0..{TOTP_TIME_MAX} Unix seconds, got {value}",
        )
    return value


# ---------------------------------------------------------------------------
# Base32 (RFC 4648)
# ---------------------------------------------------------------------------


def b32encode(data: bytes, *, pad: bool = True) -> str:
    """Canonical RFC 4648 Base32: uppercase alphabet, optional '=' padding."""
    raw = bytes(data)
    bits = "".join(f"{byte:08b}" for byte in raw)
    chars: list[str] = []
    for offset in range(0, len(bits), 5):
        chunk = bits[offset : offset + 5].ljust(5, "0")
        chars.append(BASE32_ALPHABET[int(chunk, 2)])
    encoded = "".join(chars)
    if pad:
        encoded += "=" * ((8 - len(encoded) % 8) % 8)
    return encoded


def b32decode(text: str, *, tolerant: bool = True) -> bytes:
    """Decode RFC 4648 Base32.

    Tolerant mode ignores ASCII whitespace and '-' and accepts lower case and
    omitted padding (the forms users paste from password managers).  In both
    modes '=' may only appear as trailing padding, padding must be the exact
    canonical amount, and unused trailing bits must be zero.

    Raises :class:`OtpError` with one of the ``BASE32_*`` codes.
    """
    if not isinstance(text, str):
        raise OtpError(E_BASE32_INVALID_CHAR, "Base32 input must be a string")

    candidate = text
    if tolerant:
        candidate = "".join(ch for ch in candidate if ch not in BASE32_TOLERANT_IGNORED)
        candidate = candidate.upper()

    if any(ch not in BASE32_ALPHABET and ch != "=" for ch in candidate):
        raise OtpError(E_BASE32_INVALID_CHAR, "Base32 input contains a non-alphabet character")

    first_pad = candidate.find("=")
    if first_pad >= 0:
        padding = candidate[first_pad:]
        if any(ch != "=" for ch in padding):
            raise OtpError(E_BASE32_INVALID_PADDING, "'=' may only appear as trailing padding")
        data_part = candidate[:first_pad]
        padding_len = len(candidate) - first_pad
        if len(candidate) % 8 != 0:
            raise OtpError(E_BASE32_INVALID_PADDING, "padded Base32 length must be a multiple of 8")
        if len(data_part) % 8 not in BASE32_VALID_DATA_LENGTHS_MOD_8:
            raise OtpError(E_BASE32_INVALID_LENGTH, "Base32 data length cannot encode whole bytes")
        expected_padding = (8 - len(data_part) % 8) % 8
        if padding_len != expected_padding:
            raise OtpError(
                E_BASE32_INVALID_PADDING,
                f"expected {expected_padding} padding characters, got {padding_len}",
            )
        if expected_padding == 0:
            raise OtpError(E_BASE32_INVALID_PADDING, "padding is not allowed for a complete group")
    else:
        data_part = candidate
        if len(data_part) % 8 not in BASE32_VALID_DATA_LENGTHS_MOD_8:
            raise OtpError(E_BASE32_INVALID_LENGTH, "Base32 data length cannot encode whole bytes")

    output = bytearray()
    buffer = 0
    buffered_bits = 0
    for char in data_part:
        buffer = (buffer << 5) | BASE32_VALUE[char]
        buffered_bits += 5
        while buffered_bits >= 8:
            buffered_bits -= 8
            output.append((buffer >> buffered_bits) & 0xFF)
            buffer &= (1 << buffered_bits) - 1

    if buffered_bits and buffer != 0:
        raise OtpError(
            E_BASE32_INVALID_TRAILING_BITS,
            "non-zero unused bits in the final Base32 character",
        )
    return bytes(output)


# ---------------------------------------------------------------------------
# HOTP / TOTP
# ---------------------------------------------------------------------------


def hotp(
    secret: bytes,
    counter: int,
    digits: int = 6,
    algorithm: str = "SHA1",
) -> str:
    """RFC 4226 HOTP.  ``counter`` is encoded as an unsigned 64-bit BE integer."""
    canonical_algorithm = validate_algorithm(algorithm)
    raw_secret = validate_secret(secret)
    digit_count = validate_digits(digits)
    counter_value = validate_counter(counter)

    message = counter_value.to_bytes(8, "big", signed=False)
    digest = hmac.new(raw_secret, message, HASH_BY_ALGORITHM[canonical_algorithm]).digest()
    offset = digest[-1] & 0x0F
    binary = (
        ((digest[offset] & 0x7F) << 24)
        | ((digest[offset + 1] & 0xFF) << 16)
        | ((digest[offset + 2] & 0xFF) << 8)
        | (digest[offset + 3] & 0xFF)
    )
    code = binary % (10**digit_count)
    return str(code).zfill(digit_count)


def totp(
    secret: bytes,
    unix_seconds: int,
    digits: int = 6,
    period: int = 30,
    algorithm: str = "SHA1",
) -> str:
    """RFC 6238 TOTP using floor(unix_seconds / period)."""
    canonical_algorithm = validate_algorithm(algorithm)
    raw_secret = validate_secret(secret)
    digit_count = validate_digits(digits)
    period_value = validate_period(period)
    time_value = validate_time(unix_seconds)
    return hotp(raw_secret, time_value // period_value, digit_count, canonical_algorithm)


def remaining_seconds(unix_seconds: int, period: int = 30) -> int:
    """Seconds until the current TOTP window ends (design doc §8.1)."""
    time_value = validate_time(unix_seconds)
    period_value = validate_period(period)
    return period_value - (time_value % period_value)


def code_from_request(request: dict[str, Any]) -> str:
    """Run a machine-readable ``vectors.json`` boundary request.

    Request shape:
      {"kind": "totp", "time": ..., "secretHex": ..., "algorithm": ...,
       "digits": ..., "period": ...}
      {"kind": "hotp", "counter": ..., "secretHex": ..., "algorithm": ...,
       "digits": ...}
    """
    if not isinstance(request, dict):
        raise OtpError(E_REQUEST_INVALID, "request must be an object")
    kind = request.get("kind")
    secret_hex = request.get("secretHex", "")
    if not isinstance(secret_hex, str):
        raise OtpError(E_SECRET_HEX_INVALID, "secretHex must be a string")
    try:
        secret = bytes.fromhex(secret_hex)
    except ValueError as exc:
        raise OtpError(E_SECRET_HEX_INVALID, "secretHex is not valid hex") from exc

    if kind == "totp":
        return totp(
            secret,
            request.get("time"),
            request.get("digits", 6),
            request.get("period", 30),
            request.get("algorithm", "SHA1"),
        )
    if kind == "hotp":
        return hotp(
            secret,
            request.get("counter"),
            request.get("digits", 6),
            request.get("algorithm", "SHA1"),
        )
    raise OtpError(E_KIND_UNSUPPORTED, f"unsupported request kind: {kind!r}")


# ---------------------------------------------------------------------------
# RFC test vectors (public values; verified against the implementation below)
# ---------------------------------------------------------------------------

RFC4226_SECRET = b"12345678901234567890"
RFC4226_EXPECTED = (
    "755224",
    "287082",
    "359152",
    "969429",
    "338314",
    "254676",
    "287922",
    "162583",
    "399871",
    "520489",
)

RFC6238_TIMES = (59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000)
RFC6238_SECRETS = {
    "SHA1": b"1234567890" * 2,  # 20 bytes
    "SHA256": b"1234567890" * 3 + b"12",  # 32 bytes
    "SHA512": b"1234567890" * 6 + b"1234",  # 64 bytes
}
RFC6238_EXPECTED = {
    59: {"SHA1": "94287082", "SHA256": "46119246", "SHA512": "90693936"},
    1111111109: {"SHA1": "07081804", "SHA256": "68084774", "SHA512": "25091201"},
    1111111111: {"SHA1": "14050471", "SHA256": "67062674", "SHA512": "99943326"},
    1234567890: {"SHA1": "89005924", "SHA256": "91819424", "SHA512": "93441116"},
    2000000000: {"SHA1": "69279037", "SHA256": "90698825", "SHA512": "38618901"},
    20000000000: {"SHA1": "65353130", "SHA256": "77737706", "SHA512": "47863826"},
}

# (input, expectedHex, note) decoded in tolerant mode; expectedHex is from the
# public RFC 4648 §10 examples or is the canonical encoding of the same bytes.
BASE32_VALID = (
    ("", "", "empty input is a valid empty byte string"),
    ("MY======", "66", "RFC 4648: 'f'"),
    ("MZXQ====", "666f", "RFC 4648: 'fo'"),
    ("MZXW6===", "666f6f", "RFC 4648: 'foo'"),
    ("MZXW6YQ=", "666f6f62", "RFC 4648: 'foob'"),
    ("MZXW6YTB", "666f6f6261", "RFC 4648: 'fooba'"),
    ("MZXW6YTBOI======", "666f6f626172", "RFC 4648: 'foobar'"),
    ("mzxw6ytboi======", "666f6f626172", "tolerant: lower case"),
    ("MZXW 6YTB OI", "666f6f626172", "tolerant: spaces"),
    ("MZXW-6YTB-OI", "666f6f626172", "tolerant: hyphens"),
    ("  mzxw-6ytb\toi  ", "666f6f626172", "tolerant: lower case + hyphens + whitespace"),
    ("MZXW6YTBOI", "666f6f626172", "tolerant: omitted padding"),
    ("MZXW6YQ", "666f6f62", "tolerant: omitted padding, 4-byte payload"),
    ("MZXW6", "666f6f", "tolerant: omitted padding, 3-byte payload"),
    ("mzxw6yq=", "666f6f62", "tolerant: lower case with padding"),
)

# (input, expectedError) in tolerant mode.
BASE32_INVALID = (
    ("MZXW6YTB=", E_BASE32_INVALID_PADDING),
    ("MZXW6YT==", E_BASE32_INVALID_PADDING),
    ("MZXW6YQ==", E_BASE32_INVALID_PADDING),
    ("MZXW6YTB====", E_BASE32_INVALID_PADDING),
    ("========", E_BASE32_INVALID_PADDING),
    ("MZ=XW6YTB", E_BASE32_INVALID_PADDING),
    ("MZXW6YTB!", E_BASE32_INVALID_CHAR),
    ("MZXW6YTB1", E_BASE32_INVALID_CHAR),
    ("MZXW6YTB·", E_BASE32_INVALID_CHAR),
    ("A", E_BASE32_INVALID_LENGTH),
    ("MZXW6Y", E_BASE32_INVALID_LENGTH),
    ("MZXW6YT", E_BASE32_INVALID_TRAILING_BITS),
    ("MZXW6YT=", E_BASE32_INVALID_TRAILING_BITS),
    ("MZXW6YR", E_BASE32_INVALID_TRAILING_BITS),
)
# These are invalid only in strict mode: tolerant mode normalizes them.
BASE32_STRICT_INVALID = (
    ("mzxw6ytb", E_BASE32_INVALID_CHAR),
    ("MZXW 6YTB", E_BASE32_INVALID_CHAR),
    ("MZXW-6YTB", E_BASE32_INVALID_CHAR),
)



# ---------------------------------------------------------------------------
# Vector construction
# ---------------------------------------------------------------------------

RANDOM_SEED = 20261105
RANDOM_PERIODS = (1, 30, 60, 300)
RANDOM_EDGE_TIMES = (0, 1, 29, 30, 59, 60, 1111111109, 4102444800)


def _build_totp_vectors() -> list[dict[str, Any]]:
    vectors: list[dict[str, Any]] = []
    for unix_seconds in RFC6238_TIMES:
        for algorithm in ALGORITHMS:
            secret = RFC6238_SECRETS[algorithm]
            expected = totp(secret, unix_seconds, digits=8, period=30, algorithm=algorithm)
            if expected != RFC6238_EXPECTED[unix_seconds][algorithm]:
                raise AssertionError(
                    f"RFC 6238 mismatch at t={unix_seconds} {algorithm}: "
                    f"got {expected}, expected {RFC6238_EXPECTED[unix_seconds][algorithm]}"
                )
            vectors.append(
                {
                    "time": unix_seconds,
                    "algorithm": algorithm,
                    "digits": 8,
                    "period": 30,
                    "secretAscii": secret.decode("ascii"),
                    "secretBase32": b32encode(secret),
                    "expected": expected,
                }
            )
    return vectors


def _build_hotp_vectors() -> list[dict[str, Any]]:
    vectors: list[dict[str, Any]] = []
    for counter, expected in enumerate(RFC4226_EXPECTED):
        actual = hotp(RFC4226_SECRET, counter, digits=6, algorithm="SHA1")
        if actual != expected:
            raise AssertionError(
                f"RFC 4226 mismatch at counter={counter}: got {actual}, expected {expected}"
            )
        vectors.append(
            {
                "counter": counter,
                "algorithm": "SHA1",
                "digits": 6,
                "secretAscii": RFC4226_SECRET.decode("ascii"),
                "secretBase32": b32encode(RFC4226_SECRET),
                "expected": actual,
            }
        )
    return vectors


def _build_base32_vectors() -> list[dict[str, Any]]:
    vectors: list[dict[str, Any]] = []
    for text, expected_hex, note in BASE32_VALID:
        decoded = b32decode(text, tolerant=True)
        if decoded.hex() != expected_hex:
            raise AssertionError(
                f"Base32 decode mismatch for {text!r}: got {decoded.hex()}, expected {expected_hex}"
            )
        vectors.append(
            {
                "input": text,
                "expectedHex": expected_hex,
                "expectedBase32": b32encode(decoded),
                "note": note,
            }
        )
    for text, expected_error in BASE32_INVALID:
        try:
            b32decode(text, tolerant=True)
        except OtpError as exc:
            if exc.code != expected_error:
                raise AssertionError(
                    f"Base32 error mismatch for {text!r}: got {exc.code}, expected {expected_error}"
                ) from exc
        else:
            raise AssertionError(f"Base32 input {text!r} unexpectedly decoded")
        vectors.append({"input": text, "expectedError": expected_error})
    return vectors


def _build_boundary_vectors() -> list[dict[str, Any]]:
    rfc_secret_hex = RFC4226_SECRET.hex()
    cases: list[dict[str, Any]] = [
        {
            "description": "digits=5 is not supported",
            "request": {
                "kind": "totp",
                "time": 59,
                "secretHex": rfc_secret_hex,
                "algorithm": "SHA1",
                "digits": 5,
                "period": 30,
            },
            "expectedError": E_DIGITS_UNSUPPORTED,
        },
        {
            "description": "digits=7 is not supported",
            "request": {
                "kind": "totp",
                "time": 59,
                "secretHex": rfc_secret_hex,
                "algorithm": "SHA1",
                "digits": 7,
                "period": 30,
            },
            "expectedError": E_DIGITS_UNSUPPORTED,
        },
        {
            "description": "period=0 is rejected",
            "request": {
                "kind": "totp",
                "time": 59,
                "secretHex": rfc_secret_hex,
                "algorithm": "SHA1",
                "digits": 6,
                "period": 0,
            },
            "expectedError": E_PERIOD_OUT_OF_RANGE,
        },
        {
            "description": "period=301 is rejected",
            "request": {
                "kind": "totp",
                "time": 59,
                "secretHex": rfc_secret_hex,
                "algorithm": "SHA1",
                "digits": 6,
                "period": 301,
            },
            "expectedError": E_PERIOD_OUT_OF_RANGE,
        },
        {
            "description": "MD5 is not a supported HMAC algorithm",
            "request": {
                "kind": "totp",
                "time": 59,
                "secretHex": rfc_secret_hex,
                "algorithm": "MD5",
                "digits": 6,
                "period": 30,
            },
            "expectedError": E_ALGORITHM_UNSUPPORTED,
        },
        {
            "description": "empty secret is rejected",
            "request": {
                "kind": "totp",
                "time": 59,
                "secretHex": "",
                "algorithm": "SHA1",
                "digits": 6,
                "period": 30,
            },
            "expectedError": E_SECRET_EMPTY,
        },
        {
            "description": "secret longer than 255 bytes is rejected",
            "request": {
                "kind": "totp",
                "time": 59,
                "secretHex": "ab" * 256,
                "algorithm": "SHA1",
                "digits": 6,
                "period": 30,
            },
            "expectedError": E_SECRET_TOO_LONG,
        },
        {
            "description": "HOTP counter above u64 is rejected",
            "request": {
                "kind": "hotp",
                "counter": 1 << 64,
                "secretHex": rfc_secret_hex,
                "algorithm": "SHA1",
                "digits": 6,
            },
            "expectedError": E_COUNTER_OVERFLOW,
        },
        {
            "description": "negative HOTP counter is rejected",
            "request": {
                "kind": "hotp",
                "counter": -1,
                "secretHex": rfc_secret_hex,
                "algorithm": "SHA1",
                "digits": 6,
            },
            "expectedError": E_COUNTER_NEGATIVE,
        },
        {
            "description": "negative TOTP time is rejected",
            "request": {
                "kind": "totp",
                "time": -1,
                "secretHex": rfc_secret_hex,
                "algorithm": "SHA1",
                "digits": 6,
                "period": 30,
            },
            "expectedError": E_TIME_OUT_OF_RANGE,
        },
        {
            "description": "TOTP time above i64 is rejected",
            "request": {
                "kind": "totp",
                "time": 1 << 63,
                "secretHex": rfc_secret_hex,
                "algorithm": "SHA1",
                "digits": 6,
                "period": 30,
            },
            "expectedError": E_TIME_OUT_OF_RANGE,
        },
        {
            "description": "unknown request kind is rejected",
            "request": {
                "kind": "motp",
                "time": 59,
                "secretHex": rfc_secret_hex,
                "algorithm": "SHA1",
                "digits": 6,
                "period": 30,
            },
            "expectedError": E_KIND_UNSUPPORTED,
        },
    ]
    for case in cases:
        try:
            code_from_request(case["request"])
        except OtpError as exc:
            if exc.code != case["expectedError"]:
                raise AssertionError(
                    f"boundary case {case['description']!r}: got {exc.code}, "
                    f"expected {case['expectedError']}"
                ) from exc
        else:
            raise AssertionError(f"boundary case {case['description']!r} unexpectedly succeeded")
    return cases


def _build_random_totp_vectors() -> list[dict[str, Any]]:
    """Deterministic coverage: 3 algorithms x 2 digit counts x 4 periods,
    plus every secret length from 1 to 64."""
    rng = random.Random(RANDOM_SEED)
    combinations = [
        (algorithm, digits, period)
        for algorithm in ALGORITHMS
        for digits in DIGITS_ALLOWED
        for period in RANDOM_PERIODS
    ]
    vectors: list[dict[str, Any]] = []
    total = 72
    for index in range(total):
        algorithm, digits, period = combinations[index % len(combinations)]
        length = index + 1 if index < 64 else rng.randint(1, 64)
        secret = bytes(rng.randrange(0, 256) for _ in range(length))
        if index < len(RANDOM_EDGE_TIMES):
            unix_seconds = RANDOM_EDGE_TIMES[index]
        else:
            unix_seconds = rng.randint(0, 4102444800)  # through 2100-01-01
        expected = totp(secret, unix_seconds, digits=digits, period=period, algorithm=algorithm)
        vectors.append(
            {
                "time": unix_seconds,
                "algorithm": algorithm,
                "digits": digits,
                "period": period,
                "secretBase32": b32encode(secret),
                "expected": expected,
            }
        )
    return vectors


def build_vectors() -> dict[str, Any]:
    return {
        "version": 1,
        "meta": {
            "description": "InkOTP platform-neutral OTP test vectors",
            "generator": "tools/reference_totp.py",
            "seed": RANDOM_SEED,
            "references": [
                "RFC 4226 Appendix D",
                "RFC 6238 Appendix B",
                "RFC 4648 Section 10",
            ],
            "notes": (
                "secretBase32 is canonical RFC 4648 Base32 (uppercase, '=' padded "
                "when needed). Decoders must also accept unpadded, lower-case, "
                "space-separated and hyphen-separated input."
            ),
        },
        "totp": _build_totp_vectors(),
        "hotp": _build_hotp_vectors(),
        "base32": _build_base32_vectors(),
        "boundary": _build_boundary_vectors(),
        "random_totp": _build_random_totp_vectors(),
    }


def write_vectors(path: Path) -> dict[str, Any]:
    vectors = build_vectors()
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(vectors, ensure_ascii=False, indent=2) + "\n"
    path.write_text(text, encoding="utf-8")
    return vectors


# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------


def _check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def _selftest_rfc() -> int:
    checks = 0
    for counter, expected in enumerate(RFC4226_EXPECTED):
        actual = hotp(RFC4226_SECRET, counter, digits=6, algorithm="SHA1")
        _check(actual == expected, f"RFC 4226 counter={counter}: {actual} != {expected}")
        checks += 1
    for unix_seconds in RFC6238_TIMES:
        for algorithm in ALGORITHMS:
            actual = totp(
                RFC6238_SECRETS[algorithm],
                unix_seconds,
                digits=8,
                period=30,
                algorithm=algorithm,
            )
            expected = RFC6238_EXPECTED[unix_seconds][algorithm]
            _check(
                actual == expected,
                f"RFC 6238 t={unix_seconds} {algorithm}: {actual} != {expected}",
            )
            checks += 1
    return checks


def _selftest_base32() -> int:
    checks = 0
    # RFC 4648 examples plus tolerant forms.
    for text, expected_hex, _note in BASE32_VALID:
        decoded = b32decode(text, tolerant=True)
        _check(decoded.hex() == expected_hex, f"Base32 decode {text!r}")
        checks += 1
    for text, expected_error in BASE32_INVALID:
        try:
            b32decode(text, tolerant=True)
        except OtpError as exc:
            _check(exc.code == expected_error, f"Base32 error {text!r}: {exc.code}")
        else:
            raise AssertionError(f"Base32 input {text!r} unexpectedly decoded")
        checks += 1
    for text, expected_error in (
        ("mzxw6ytb", E_BASE32_INVALID_CHAR),
        ("MZXW 6YTB", E_BASE32_INVALID_CHAR),
        ("MZXW-6YTB", E_BASE32_INVALID_CHAR),
    ):
        try:
            b32decode(text, tolerant=False)
        except OtpError as exc:
            _check(exc.code == expected_error, f"strict Base32 error {text!r}: {exc.code}")
        else:
            raise AssertionError(f"strict Base32 input {text!r} unexpectedly decoded")
        checks += 1
    # Always check empty across every data length, including non-zero bytes.
    for length in range(0, 65):
        data = bytes((length * 31 + index * 17) & 0xFF for index in range(length))
        encoded = b32encode(data)
        _check(b32encode(data, pad=False) == encoded.rstrip("="), "unpadded encode")
        _check(b32decode(encoded, tolerant=False) == data, f"strict round-trip len={length}")
        _check(b32decode(encoded.rstrip("="), tolerant=True) == data, f"unpadded round-trip len={length}")
        checks += 2
    return checks


def _selftest_domain() -> int:
    checks = 0
    cases = (
        (lambda: hotp(b"", 0), E_SECRET_EMPTY),
        (lambda: hotp(b"x" * 256, 0), E_SECRET_TOO_LONG),
        (lambda: hotp(RFC4226_SECRET, 0, digits=5), E_DIGITS_UNSUPPORTED),
        (lambda: hotp(RFC4226_SECRET, 0, digits=7), E_DIGITS_UNSUPPORTED),
        (lambda: hotp(RFC4226_SECRET, 0, algorithm="MD5"), E_ALGORITHM_UNSUPPORTED),
        (lambda: hotp(RFC4226_SECRET, -1), E_COUNTER_NEGATIVE),
        (lambda: hotp(RFC4226_SECRET, 1 << 64), E_COUNTER_OVERFLOW),
        (lambda: totp(RFC4226_SECRET, 59, period=0), E_PERIOD_OUT_OF_RANGE),
        (lambda: totp(RFC4226_SECRET, 59, period=301), E_PERIOD_OUT_OF_RANGE),
        (lambda: totp(RFC4226_SECRET, -1), E_TIME_OUT_OF_RANGE),
        (lambda: totp(RFC4226_SECRET, 1 << 63), E_TIME_OUT_OF_RANGE),
    )
    for call, expected_code in cases:
        try:
            call()
        except OtpError as exc:
            _check(exc.code == expected_code, f"domain error: {exc.code} != {expected_code}")
        else:
            raise AssertionError(f"expected {expected_code}")
        checks += 1
    _check(remaining_seconds(59, 30) == 1, "remaining_seconds")
    checks += 1
    return checks


def _selftest_vectors(path: Path) -> tuple[int, str]:
    if not path.exists():
        return 0, f"{path} not found (skipped)"
    payload = json.loads(path.read_text(encoding="utf-8"))
    checks = 0
    _check(payload.get("version") == 1, "vectors version must be 1")
    _check(len(payload["totp"]) == 18, "expected 18 RFC 6238 TOTP vectors")
    _check(len(payload["hotp"]) == 10, "expected 10 RFC 4226 HOTP vectors")

    for vector in payload["totp"]:
        secret = b32decode(vector["secretBase32"], tolerant=True)
        if "secretAscii" in vector:
            _check(secret == vector["secretAscii"].encode("ascii"), "TOTP secretAscii mismatch")
        actual = totp(
            secret,
            vector["time"],
            vector["digits"],
            vector["period"],
            vector["algorithm"],
        )
        _check(actual == vector["expected"], f"TOTP vector mismatch: {vector}")
        checks += 1

    for vector in payload["hotp"]:
        secret = b32decode(vector["secretBase32"], tolerant=True)
        if "secretAscii" in vector:
            _check(secret == vector["secretAscii"].encode("ascii"), "HOTP secretAscii mismatch")
        actual = hotp(secret, vector["counter"], vector["digits"], vector["algorithm"])
        _check(actual == vector["expected"], f"HOTP vector mismatch: {vector}")
        checks += 1

    for vector in payload["base32"]:
        if "expectedError" in vector:
            try:
                b32decode(vector["input"], tolerant=True)
            except OtpError as exc:
                _check(exc.code == vector["expectedError"], f"Base32 error vector: {vector}")
            else:
                raise AssertionError(f"Base32 error vector decoded: {vector}")
        else:
            decoded = b32decode(vector["input"], tolerant=True)
            _check(decoded.hex() == vector["expectedHex"], f"Base32 hex vector: {vector}")
            if "expectedBase32" in vector:
                _check(b32encode(decoded) == vector["expectedBase32"], f"Base32 encode vector: {vector}")
        checks += 1

    for case in payload["boundary"]:
        try:
            code_from_request(case["request"])
        except OtpError as exc:
            _check(exc.code == case["expectedError"], f"boundary vector: {case}")
        else:
            raise AssertionError(f"boundary vector unexpectedly succeeded: {case}")
        checks += 1

    random_vectors = payload["random_totp"]
    _check(len(random_vectors) >= 50, "expected at least 50 random TOTP vectors")
    algorithms: set[str] = set()
    digit_set: set[int] = set()
    periods: set[int] = set()
    lengths: set[int] = set()
    for vector in random_vectors:
        secret = b32decode(vector["secretBase32"], tolerant=True)
        lengths.add(len(secret))
        algorithms.add(vector["algorithm"])
        digit_set.add(vector["digits"])
        periods.add(vector["period"])
        actual = totp(
            secret,
            vector["time"],
            vector["digits"],
            vector["period"],
            vector["algorithm"],
        )
        _check(actual == vector["expected"], f"random TOTP vector mismatch: {vector}")
        checks += 1
    _check(algorithms == set(ALGORITHMS), f"random algorithm coverage: {algorithms}")
    _check(digit_set == set(DIGITS_ALLOWED), f"random digits coverage: {digit_set}")
    _check(periods == set(RANDOM_PERIODS), f"random period coverage: {periods}")
    _check(lengths == set(range(1, 65)), f"random secret-length coverage missing: "
           f"{sorted(set(range(1, 65)) - lengths)}")
    return checks, f"{path} ({len(random_vectors)} random)"


def selftest(vectors_path: Path) -> int:
    total = 0
    rfc_checks = _selftest_rfc()
    print(
        f"RFC 4226/6238 vectors ................ {rfc_checks}/{rfc_checks} "
        f"({len(RFC4226_EXPECTED)} HOTP + "
        f"{len(RFC6238_TIMES) * len(ALGORITHMS)} TOTP)"
    )
    total += rfc_checks
    base32_checks = _selftest_base32()
    print(f"Base32 RFC 4648 + tolerant/invalid ... {base32_checks}/{base32_checks}")
    total += base32_checks
    domain_checks = _selftest_domain()
    print(f"Domain limits ........................ {domain_checks}/{domain_checks}")
    total += domain_checks
    vector_checks, label = _selftest_vectors(vectors_path)
    if vector_checks:
        print(f"vectors.json ......................... {vector_checks}/{vector_checks}  {label}")
        total += vector_checks
    else:
        print(f"vectors.json ......................... SKIPPED  ({label})")
    print(f"SELFTEST PASS ({total} checks)")
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Independent RFC 4226/6238/4648 reference implementation for InkOTP.",
    )
    parser.add_argument("--selftest", action="store_true", help="run RFC vectors and verify spec/vectors.json")
    parser.add_argument(
        "--generate-vectors",
        nargs="?",
        const=str(DEFAULT_VECTORS),
        metavar="PATH",
        help="write the full deterministic vectors file (default: spec/vectors.json)",
    )
    parser.add_argument(
        "--vectors",
        metavar="PATH",
        default=str(DEFAULT_VECTORS),
        help="vectors file used by --selftest (default: spec/vectors.json)",
    )
    args = parser.parse_args(argv)

    if not args.selftest and not args.generate_vectors:
        parser.print_help()
        return 2

    try:
        if args.generate_vectors:
            path = Path(args.generate_vectors)
            vectors = write_vectors(path)
            print(
                f"wrote {path}: "
                f"{len(vectors['totp'])} totp, "
                f"{len(vectors['hotp'])} hotp, "
                f"{len(vectors['base32'])} base32, "
                f"{len(vectors['boundary'])} boundary, "
                f"{len(vectors['random_totp'])} random_totp"
            )
        if args.selftest:
            return selftest(Path(args.vectors))
    except (AssertionError, OtpError, KeyError, OSError, ValueError) as exc:
        print(f"SELFTEST FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
