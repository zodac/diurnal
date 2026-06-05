#!/usr/bin/env bash
# Generates an RSA-2048 keypair for JWT signing.
# Usage:
#   ./scripts/generate-jwt-keys.sh          # writes to secrets/ (Docker mount)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/secrets"

mkdir -p "$DEST"

TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

openssl genrsa -out "$TMP" 2048 2>/dev/null
openssl pkcs8 -topk8 -inform PEM -in "$TMP" -out "$DEST/private.pem" -nocrypt
openssl rsa -in "$TMP" -pubout -out "$DEST/public.pem" 2>/dev/null
chmod 600 "$DEST/private.pem"

echo "JWT keys written to $DEST/"
echo "  private.pem  — signing key, keep secret"
echo "  public.pem   — verification key"
