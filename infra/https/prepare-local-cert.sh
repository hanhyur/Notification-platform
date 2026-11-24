#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 1 || -z ${1:-} ]]; then
    echo "Usage: bash prepare-local-cert.sh <certificate-directory>" >&2
    exit 2
fi
: "${LOCAL_TLS_PASSWORD:?Set LOCAL_TLS_PASSWORD for the local keystore}"
mkdir -p -- "$1"
CDPATH= cd -- "$1/."

for file in localhost.p12 localhost.pem; do
    if [[ -L $file || ( -e $file && ! -f $file ) ]]; then
        echo "Expected a regular file at $file" >&2
        exit 1
    fi
done
if [[ -f localhost.pem && ! -f localhost.p12 ]]; then
    echo "Found localhost.pem without its private key. Choose a new directory." >&2
    exit 1
fi

staging=$(mktemp -d ./.localhost-cert.XXXXXXXX)
trap 'rm -rf -- "$staging"' EXIT
trap 'exit 1' HUP INT TERM
options=(-alias localhost -storetype PKCS12 -storepass:env LOCAL_TLS_PASSWORD)

if [[ ! -f localhost.p12 ]]; then
    keytool -genkeypair "${options[@]}" -keystore "$staging/localhost.p12" \
        -keyalg RSA -keysize 2048 -validity 30 -dname CN=localhost \
        -ext SAN=DNS:localhost,IP:127.0.0.1 -ext EKU=serverAuth
    ln "$staging/localhost.p12" localhost.p12 || [[ -f localhost.p12 ]]
fi

keytool -exportcert "${options[@]}" -keystore localhost.p12 -rfc > "$staging/localhost.pem"
if ! openssl x509 -in "$staging/localhost.pem" -checkend 0 -noout > /dev/null; then
    echo "Could not confirm certificate validity. If it has expired, choose a new directory." >&2
    exit 1
fi
if [[ ! -f localhost.pem ]]; then
    ln "$staging/localhost.pem" localhost.pem || [[ -f localhost.pem ]]
fi
if ! cmp -s "$staging/localhost.pem" localhost.pem; then
    echo "localhost.pem does not match the keystore. Choose a new directory." >&2
    exit 1
fi
echo "Local TLS files ready in $PWD"
