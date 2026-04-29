#!/usr/bin/env sh

OUT_DIR="$(dirname "$0")/.certs"
mkdir -p "$OUT_DIR"

PASS_FILE="$OUT_DIR/pass"


# Ensure the password file exists before proceeding
if [ ! -f "$PASS_FILE" ]; then
    echo "Error: Password file not found at: $PASS_FILE. Please create it first." >&2
    exit 1
fi

# Read the file and trim all leading/trailing whitespace (including newlines)
OPENSSL_PASS="$(cat "$PASS_FILE" | tr -d '\n\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
export OPENSSL_PASS

# Ensure the trimmed password meets OpenSSL's 4-character minimum
if [ "${#OPENSSL_PASS}" -lt 4 ]; then
    echo "Error: Password must be at least 4 characters long (excluding surrounding spaces)." >&2
    exit 1
fi

# Generate encrypted key using the trimmed password from the environment variable
openssl genpkey -aes-256-cbc -algorithm RSA -out "$OUT_DIR/private_encrypted.pem" -pkeyopt rsa_keygen_bits:4096 -pass "env:OPENSSL_PASS"

# Decrypt to standard key using the same environment variable
openssl rsa -in "$OUT_DIR/private_encrypted.pem" -out "$OUT_DIR/private.pem" -passin "env:OPENSSL_PASS"

# Generate the certificate chain
openssl req -key "$OUT_DIR/private.pem" -new -x509 -days 365 -out "$OUT_DIR/chain.crt"

# Generate base64 versions for environments that need them (e.g. CI/CD secrets)
openssl base64 -in "$OUT_DIR/chain.crt" -A -out "$OUT_DIR/chain_base64"
openssl base64 -in "$OUT_DIR/private_encrypted.pem" -A -out "$OUT_DIR/private_encrypted_base64"

# Clean up the environment variable so it doesn't linger
unset OPENSSL_PASS
