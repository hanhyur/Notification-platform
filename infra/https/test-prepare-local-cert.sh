#!/usr/bin/env bash
set -euo pipefail
umask 077

prepare="$(dirname "$0")/prepare-local-cert.sh"
workspace=$(mktemp -d)
trap 'rm -rf -- "$workspace"' EXIT
LOCAL_TLS_PASSWORD=$(openssl rand -hex 24)
export LOCAL_TLS_PASSWORD

valid="$workspace/valid certificates"
bash "$prepare" "$valid"
openssl verify -purpose sslserver -CAfile "$valid/localhost.pem" "$valid/localhost.pem"
cp -R "$valid" "$workspace/valid-before"
bash "$prepare" "$valid"
diff -qr "$workspace/valid-before" "$valid"

expired="$workspace/expired certificates"
mkdir "$expired"
options=(-alias localhost -storetype PKCS12 -keystore "$expired/localhost.p12" -storepass:env LOCAL_TLS_PASSWORD)
keytool -genkeypair "${options[@]}" -keyalg RSA -keysize 2048 -dname CN=localhost \
    -ext SAN=DNS:localhost,IP:127.0.0.1 -ext EKU=serverAuth -startdate -3d -validity 1
keytool -exportcert "${options[@]}" -rfc > "$expired/localhost.pem"
cp -R "$expired" "$workspace/expired-before"
if bash "$prepare" "$expired"; then
    echo "Expected the expired local certificate to be rejected." >&2
    exit 1
fi
diff -qr "$workspace/expired-before" "$expired"
