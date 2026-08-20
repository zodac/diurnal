#!/usr/bin/env bash
#
# Regenerates translations/msg_en-GB.properties, the SOURCE-LANGUAGE snapshot uploaded to a translation
# management tool (CrowdIn/Weblate - see code-quality-config-overrides/crowdin.yml and .claude/I18N.md's
# "Translation tooling" section).
#
# Why this exists rather than just committing src/main/resources/messages/msg_en-GB.properties: Quarkus's
# @MessageBundle mechanism ALWAYS resolves the default locale (quarkus.default-locale=en-GB) from each method's
# @Message(...) annotation value, never from a same-named properties file, even when one exists on the classpath
# - verified directly, not assumed. So this script's output is a ONE-WAY EXPORT for external tooling only: the
# running application never reads translations/msg_en-GB.properties, and nothing here changes that mechanism.
# The application's real English content stays exactly where .claude/I18N.md's Phase 0 put it - inline in
# AppMessages.java's @Message annotations - which remains the only thing to hand-edit for an English wording
# change.
#
# A translator's output for a genuinely NEW language still lands as a real, live
# src/main/resources/messages/msg_<locale>.properties, same as msg_ar-SA.properties/msg_ja-JP.properties/
# msg_es-ES.properties today - this script only produces the SOURCE side of that workflow.
#
# Re-run this after any @Message wording change in AppMessages.java, before syncing to CrowdIn/Weblate - the
# output is committed (masters + generated output both tracked, no build-time generation, the same pattern
# scripts/generate-noto-fonts.py already uses), not regenerated as part of `mvn package`.
#
# Usage:  scripts/generate-source-messages.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

echo "-> Compiling main sources..."
if ! mvn -q compile; then
  echo "x mvn compile failed" >&2
  exit 1
fi

echo "-> Resolving the runtime classpath..."
if ! mvn -q org.apache.maven.plugins:maven-dependency-plugin:build-classpath -Dmdep.outputFile="${SCRATCH}/classpath.txt"; then
  echo "x could not resolve the runtime classpath" >&2
  exit 1
fi
RUNTIME_CP="$(cat "${SCRATCH}/classpath.txt")"

echo "-> Compiling the dumper..."
if ! javac -cp "target/classes:${RUNTIME_CP}" -d "${SCRATCH}" scripts/GenerateSourceMessages.java; then
  echo "x could not compile scripts/GenerateSourceMessages.java" >&2
  exit 1
fi

mkdir -p translations
{
  echo "# English (UK) message-bundle SOURCE content for web.AppMessages - GENERATED, do not hand-edit."
  echo "# Regenerate with scripts/generate-source-messages.sh after any @Message wording change."
  echo "#"
  echo "# NOT read by the running application (Quarkus resolves the default locale from the @Message"
  echo "# annotations, never from a properties file - see that script's own header for why). This file"
  echo "# exists only as the source-language snapshot for CrowdIn/Weblate - see"
  echo "# code-quality-config-overrides/crowdin.yml and .claude/I18N.md."
  echo
  java -cp "${SCRATCH}:target/classes:${RUNTIME_CP}" GenerateSourceMessages
} >translations/msg_en-GB.properties

echo "OK wrote translations/msg_en-GB.properties"
