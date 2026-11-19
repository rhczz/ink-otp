#ifndef INKOTP_CRYPTO_H
#define INKOTP_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

/* Each function returns 1 on success and writes a 32-byte key. */
int inkotp_argon2id(const uint8_t *password, size_t password_length,
                   const uint8_t *salt, size_t salt_length,
                   uint32_t memory_kib, uint32_t iterations, uint32_t parallelism,
                   uint8_t *key);

int inkotp_scrypt(const uint8_t *password, size_t password_length,
                  const uint8_t *salt, size_t salt_length,
                  uint64_t n, uint64_t r, uint64_t p, uint8_t *key);

int inkotp_pbkdf2_sha256(const uint8_t *password, size_t password_length,
                         const uint8_t *salt, size_t salt_length, uint8_t *key);

#endif
