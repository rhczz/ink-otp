# InkOTP release rules.
#
# BouncyCastle Argon2 is used through its lightweight API (no reflection), so no
# keep rule is expected. If R8 starts stripping needed BC classes, add the
# narrowest rule here and document it in docs/exceptions.md (C-17/C-18).
