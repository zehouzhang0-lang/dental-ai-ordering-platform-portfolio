#!/usr/bin/env bash
set -euo pipefail

homebrew_jdk21="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
elif [[ -x "${homebrew_jdk21}/bin/java" ]]; then
  export JAVA_HOME="${homebrew_jdk21}"
  export PATH="${JAVA_HOME}/bin:/opt/homebrew/bin:${PATH}"
else
  unset JAVA_HOME
fi

if ! command -v java >/dev/null 2>&1; then
  echo "Java 21 is required, but no java executable was found." >&2
  exit 1
fi

java_version_line="$(java -version 2>&1 | head -n 1)"
if ! grep -Eq 'version "21([."]|$)' <<<"${java_version_line}"; then
  echo "Java 21 is required; found: ${java_version_line}" >&2
  exit 1
fi

exec "$@"
