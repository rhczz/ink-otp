#include "include/CInkKDF.h"
#include "argon2/argon2.h"
#include "scrypt/crypto_scrypt.h"

#include <CommonCrypto/CommonKeyDerivation.h>

int ink_argon2id(uint32_t memory_kib, uint32_t iterations, uint32_t lanes,
                 const uint8_t *password, size_t password_len,
                 const uint8_t *salt, size_t salt_len,
                 uint8_t *key, size_t key_len)
{
    return argon2id_hash_raw(iterations, memory_kib, lanes,
                             password, password_len, salt, salt_len, key, key_len);
}

int ink_scrypt(const uint8_t *password, size_t password_len,
               const uint8_t *salt, size_t salt_len,
               uint64_t n, uint32_t r, uint32_t p,
               uint8_t *key, size_t key_len)
{
    return crypto_scrypt(password, password_len, salt, salt_len, n, r, p, key, key_len);
}

int ink_pbkdf2_sha256(const uint8_t *password, size_t password_len,
                      const uint8_t *salt, size_t salt_len, uint32_t rounds,
                      uint8_t *key, size_t key_len)
{
    const char *input = password_len == 0 ? "" : (const char *)password;
    return CCKeyDerivationPBKDF(kCCPBKDF2, input, password_len, salt, salt_len,
                                kCCPRFHmacAlgSHA256, rounds, key, key_len);
}
