#ifndef CINKKDF_H
#define CINKKDF_H

#include <stddef.h>
#include <stdint.h>

int ink_argon2id(uint32_t memory_kib, uint32_t iterations, uint32_t lanes,
                 const uint8_t *password, size_t password_len,
                 const uint8_t *salt, size_t salt_len,
                 uint8_t *key, size_t key_len);

int ink_scrypt(const uint8_t *password, size_t password_len,
               const uint8_t *salt, size_t salt_len,
               uint64_t n, uint32_t r, uint32_t p,
               uint8_t *key, size_t key_len);

int ink_pbkdf2_sha256(const uint8_t *password, size_t password_len,
                      const uint8_t *salt, size_t salt_len, uint32_t rounds,
                      uint8_t *key, size_t key_len);

#endif
