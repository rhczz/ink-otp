#!/bin/bash
set -euo pipefail

if [[ $# != 2 ]]; then
    echo "Usage: build-crypto.sh ARCH INSTALL_PREFIX" >&2
    exit 2
fi

architecture="$1"
case "$architecture" in
    arm64|x86_64) ;;
    *) echo "Unsupported architecture" >&2; exit 2;;
esac
[[ "$(uname -m)" == "$architecture" ]]
prefix="$(mkdir -p "$2" && cd "$2" && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
cd "$scratch"

download() {
    local url="$1" hash="$2" filename="$3"
    curl --fail --silent --show-error --location --retry 3 --max-time 240 "$url" -o "$filename"
    printf '%s  %s\n' "$hash" "$filename" | shasum -a 256 -c -
}

argon2_version=20190702
openssl_version=3.5.9
download "https://github.com/P-H-C/phc-winner-argon2/archive/refs/tags/$argon2_version.tar.gz" \
    daf972a89577f8772602bf2eb38b6a3dd3d922bf5724d45e7f9589b5e830442c argon2.tar.gz
download "https://github.com/openssl/openssl/releases/download/openssl-$openssl_version/openssl-$openssl_version.tar.gz" \
    603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a openssl.tar.gz

mkdir argon2 openssl
tar -xzf argon2.tar.gz --strip-components=1 -C argon2
tar -xzf openssl.tar.gz --strip-components=1 -C openssl
export MACOSX_DEPLOYMENT_TARGET=14.0
jobs="$(sysctl -n hw.ncpu)"

(
    cd argon2
    make -j "$jobs" OPTTARGET=none libargon2.a
    mkdir -p "$prefix/argon2/include" "$prefix/argon2/lib"
    install -m 644 include/argon2.h "$prefix/argon2/include"
    install -m 644 libargon2.a "$prefix/argon2/lib"
)
(
    cd openssl
    # Only the static crypto archive ships. Remove sockets, HTTP, and external configuration loading.
    perl Configure "darwin64-$architecture-cc" no-shared no-module no-tests no-dso \
        no-sock no-http no-autoload-config
    make -j "$jobs" build_libs > "$scratch/openssl-build.log" 2>&1 || {
        tail -100 "$scratch/openssl-build.log" >&2
        exit 1
    }
    mkdir -p "$prefix/openssl/include" "$prefix/openssl/lib"
    ditto include/openssl "$prefix/openssl/include/openssl"
    install -m 644 libcrypto.a "$prefix/openssl/lib"
)

{
    printf 'Argon2 %s\nhttps://github.com/P-H-C/phc-winner-argon2\n\n' "$argon2_version"
    cat argon2/LICENSE
    printf '\n\nOpenSSL %s\nhttps://openssl-library.org\n\n' "$openssl_version"
    cat openssl/LICENSE.txt
} > "$prefix/ThirdPartyNotices.txt"
