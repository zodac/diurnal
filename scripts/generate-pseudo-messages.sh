#!/usr/bin/env bash
#
# Regenerates src/main/resources/messages/msg_en-XA.properties, the PSEUDOLOCALE bundle.
#
# A pseudolocale is English mechanically disguised - every letter accented, every value padded and bracketed. It is
# not a translation and is never offered in the Settings picker (Language#developerOnly); it exists so a developer
# can answer three questions a real language cannot:
#
#   * Is anything HARDCODED? A string still reading as plain English on screen never went through the bundle.
#   * Does the layout COPE? Values are padded ~35%, the expansion a European translation of English routinely
#     reaches, so a column sized around English is asked the question before a translator finds it.
#   * Is anything TRUNCATED? Each value is bracketed, so a missing closing bracket is a clipped string.
#
# Reads translations/msg_en-GB.properties, which scripts/generate-source-messages.sh produces from AppMessages -
# so run THAT first after any @Message wording change, then this. Output is committed (masters + generated output
# both tracked, no build-time generation), matching scripts/generate-source-messages.sh and generate-noto-fonts.py.
#
# To USE it, set an account's language to en-XA - the picker will not offer it:
#   curl -X PATCH .../api/v1/users/me -d '{"preferences":{"language":"en-XA"}}'
# or, for the logged-out pages, send `Accept-Language: en-XA`.
#
# Usage:  scripts/generate-pseudo-messages.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

if [[ ! -f translations/msg_en-GB.properties ]]; then
  echo "x translations/msg_en-GB.properties is missing - run scripts/generate-source-messages.sh first" >&2
  exit 1
fi

SCRATCH="$(mktemp -d)"
trap 'rm -rf "${SCRATCH}"' EXIT

echo "-> Compiling the generator..."
if ! javac -d "${SCRATCH}" scripts/GeneratePseudoMessages.java; then
  echo "x could not compile scripts/GeneratePseudoMessages.java" >&2
  exit 1
fi

if ! java -cp "${SCRATCH}" GeneratePseudoMessages; then
  echo "x could not generate the pseudolocale bundle" >&2
  exit 1
fi
