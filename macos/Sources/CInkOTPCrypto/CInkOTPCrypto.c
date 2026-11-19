#include "CInkOTPCrypto.h"

#include <argon2.h>
#include <limits.h>
#include <openssl/evp.h>

int inkotp_argon2id(const uint8_t *password, size_t password_length,
                   const uint8_t *salt, size_t salt_length,
                   uint32_t memory_kib, uint32_t iterations, uint32_t parallelism,
                   uint8_t *key) {
    return argon2id_hash_raw(iterations, memory_kib, parallelism,
                            password, password_length, salt, salt_length,
                            key, 32) == ARGON2_OK;
}

int inkotp_scrypt(const uint8_t *password, size_t password_length,
                  const uint8_t *salt, size_t salt_length,
                  uint64_t n, uint64_t r, uint64_t p, uint8_t *key) {
    /* The protocol limits the main allocation to 64 MiB; include library overhead. */
    return EVP_PBE_scrypt((const char *)password, password_length, salt, salt_length,
                          n, r, p, 72ULL * 1024 * 1024, key, 32);
}

int inkotp_pbkdf2_sha256(const uint8_t *password, size_t password_length,
                         const uint8_t *salt, size_t salt_length, uint8_t *key) {
    if (password_length > INT_MAX || salt_length > INT_MAX) {
        return 0;
    }
    return PKCS5_PBKDF2_HMAC((const char *)password, (int)password_length,
                             salt, (int)salt_length, 10000, EVP_sha256(), 32, key);
}
