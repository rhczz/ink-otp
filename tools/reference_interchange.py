#!/usr/bin/env python3
"""Independent format/crypto check for public fixtures and Kotlin interchange exports.

Uses Python hashlib and cryptography, never imports Kotlin or runs upstream scripts.
The fixture password and export password below are public test-vector values only.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

TEST_PASSWORD = "correct horse battery staple"
TWO_FAS_REFERENCE = (
    "tRViSsLKzd86Hprh4ceC2OP7xazn4rrt4xhfEUbOjxLX8Rc3mkISXE0lWbmnWfggogbBJhtYgpK6fMl1D6mtsy92R3HkdGfwuXbzLebqVFJsR7IZ2w58t938iymwG4824igYy1wi6n2WDpO1Q1P69zwJGs2F5a1qP4MyIiDSD7NCV2OvidXQCBnDlGfmz0f1BQySRkkt4ryiJeCjD2o4QsveJ9uDBUn8ELyOrESv5R5DMDkD4iAF8TXU7KyoJujd"
)


def b32(value: str) -> bytes:
    value = value.upper().replace(" ", "").replace("-", "").rstrip("=")
    return base64.b32decode(value + "=" * ((-len(value)) % 8))


def profile(kind: str, issuer: str | None, account: str | None, secret: bytes,
            algorithm: str = "SHA1", digits: int = 6, period: int = 30,
            counter: int | None = None) -> tuple:
    kind = kind.upper()
    return (kind, issuer or "", account or "", secret, algorithm.upper(), digits,
            period if kind != "HOTP" else None, counter if kind == "HOTP" else None)


def aegis_db(data: dict, password: str) -> dict:
    if isinstance(data["db"], dict):
        return data["db"]
    header = data["header"]
    master = None
    for slot in header["slots"]:
        if slot["type"] != 1:
            continue
        key = hashlib.scrypt(password.encode("utf-8"), salt=bytes.fromhex(slot["salt"]),
                             n=slot["n"], r=slot["r"], p=slot["p"], dklen=32,
                             maxmem=128 * 1024 * 1024)
        params = slot["key_params"]
        try:
            master = AESGCM(key).decrypt(bytes.fromhex(params["nonce"]),
                bytes.fromhex(slot["key"]) + bytes.fromhex(params["tag"]), None)
            break
        except Exception:
            continue
    if master is None:
        raise ValueError("Aegis public fixture authentication failed")
    params = header["params"]
    plaintext = AESGCM(master).decrypt(bytes.fromhex(params["nonce"]),
        base64.b64decode(data["db"]) + bytes.fromhex(params["tag"]), None)
    return json.loads(plaintext)


def aegis_profiles(data: dict, password: str) -> list[tuple]:
    result = []
    for item in aegis_db(data, password)["entries"]:
        info = item["info"]
        result.append(profile(item["type"], item.get("issuer"), item["name"], b32(info["secret"]),
                              info.get("algo", "SHA1"), info.get("digits", 6),
                              info.get("period", 30), info.get("counter")))
    return result


def two_fas_decrypt(value: str, password: str) -> bytes:
    ciphertext, salt, nonce = map(base64.b64decode, value.split(":"))
    key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 10000, dklen=32)
    return AESGCM(key).decrypt(nonce, ciphertext, None)


def two_fas_services(data: dict, password: str) -> list[dict]:
    if data.get("servicesEncrypted"):
        if data.get("reference"):
            reference = two_fas_decrypt(data["reference"], password).decode("utf-8")
            if reference != TWO_FAS_REFERENCE:
                raise ValueError("2FAS reference text differs from official public constant")
        return json.loads(two_fas_decrypt(data["servicesEncrypted"], password))
    return data["services"]


def two_fas_profiles(data: dict, password: str) -> list[tuple]:
    result = []
    for item in sorted(two_fas_services(data, password), key=lambda x: x.get("order", {}).get("position", 0)):
        otp = item["otp"]
        result.append(profile(otp.get("tokenType") or "TOTP", otp.get("issuer") or item.get("name"),
                              otp.get("account") or otp.get("label") or item.get("name"), b32(item["secret"]),
                              otp.get("algorithm") or "SHA1", otp.get("digits") or 6,
                              otp.get("period") or 30, otp.get("counter")))
    return result


def uri_profiles(data: bytes) -> list[tuple]:
    result = []
    for line in data.decode("utf-8-sig").splitlines():
        if not line.strip():
            continue
        uri = urlparse(line.strip())
        query = parse_qs(uri.query, keep_blank_values=True)
        raw = uri.path.removeprefix("/")
        label_issuer, account = raw.split(":", 1) if ":" in raw else ("", raw)
        result.append(profile(uri.netloc, query.get("issuer", [unquote(label_issuer)])[0], unquote(account),
                              b32(query["secret"][0]), query.get("algorithm", ["SHA1"])[0],
                              int(query.get("digits", ["6"])[0]), int(query.get("period", ["30"])[0]),
                              int(query["counter"][0]) if "counter" in query else None))
    return result


def native_profiles(data: dict) -> list[tuple]:
    return [profile(i["type"], i.get("issuer"), i["accountName"], base64.b64decode(i["secretB64"]),
                    i["algorithm"], i["digits"], i["period"], i.get("counter")) for i in data["items"]]


def verify_fixtures(folder: Path) -> None:
    manifest = json.loads((folder / "sources.json").read_text())
    for item in manifest["fixtures"]:
        raw = (folder / item["file"]).read_bytes()
        blob = b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw
        if hashlib.sha1(blob).hexdigest() != item["gitBlobSha"]:
            raise ValueError("upstream fixture bytes changed: " + item["file"])
    plain = aegis_profiles(json.loads((folder / "aegis_plain.json").read_bytes()), "test")
    encrypted = aegis_profiles(json.loads((folder / "aegis_encrypted.json").read_bytes()), "test")
    if plain != encrypted:
        raise ValueError("official Aegis plain/encrypted profiles differ")
    if plain != uri_profiles((folder / "plain.txt").read_bytes()):
        raise ValueError("official Aegis/URI profiles differ")
    for suffix in ["", "_v3", "_v4"]:
        plain = two_fas_profiles(json.loads((folder / f"2fas_authenticator_plain{suffix}.2fas").read_bytes()), "test")
        encrypted = two_fas_profiles(json.loads((folder / f"2fas_authenticator_encrypted{suffix}.2fas").read_bytes()), "test")
        if plain != encrypted:
            raise ValueError("official 2FAS plaintext/encrypted profiles differ: " + suffix)
    print("[PASS] 10 upstream fixture hashes; Aegis and 2FAS crypto/URI profile cross-checks")


def verify_exports(folder: Path, prefix: str = "kotlin") -> None:
    from reference_backup import decrypt_to_json
    expected = native_profiles(json.loads((folder / "expected.json").read_bytes()))
    checked = 0
    for file in sorted(folder.glob(f"{prefix}-*")):
        raw = file.read_bytes()
        if "OTPAUTH_TEXT" in file.name:
            actual = uri_profiles(raw)
        elif "INKOTP_2FAB" in file.name:
            actual = native_profiles(decrypt_to_json(raw, TEST_PASSWORD))
        elif "AEGIS" in file.name:
            actual = aegis_profiles(json.loads(raw), TEST_PASSWORD)
        elif "TWOFAS" in file.name:
            actual = two_fas_profiles(json.loads(raw), TEST_PASSWORD)
        else:
            raise ValueError("unexpected export fixture")
        if actual != expected:
            # Do not log accounts, secrets, passphrases or decrypted JSON.
            raise ValueError("export profile mismatch: " + file.name)
        checked += 1
        print("[PASS] independent decrypt/profile validation:", file.name)
    if checked != 6:
        raise ValueError(f"expected all six {prefix} export formats")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=Path("spec/fixtures/interoperability"))
    parser.add_argument("--exports", type=Path)
    parser.add_argument("--prefix", choices=("kotlin", "swift"), default="kotlin")
    args = parser.parse_args()
    verify_fixtures(args.fixtures)
    if args.exports:
        verify_exports(args.exports, args.prefix)
    print("INTERCHANGE CROSSCHECK PASS")


if __name__ == "__main__":
    main()
