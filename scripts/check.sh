#!/bin/bash
# Refactor guardrail: one command that must be green before anything lands.
#
#   ./scripts/check.sh
#
# Runs, in order:
#   1. :app:compileDebugKotlin --rerun-tasks   (real compile, never UP-TO-DATE)
#   2. :app:lintDebug                          (abortOnError, baseline-filtered)
#   3. :app:testDebugUnitTest                  (fast JVM tests, no device)
#
# Never installs, never touches a device.
set -euo pipefail

# shellcheck source=scripts/common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"   # JAVA_HOME/ANDROID_HOME + cd to repo root

# Sibling worktrees build concurrently on this machine.
GRADLE_ARGS=(--max-workers=2)

echo "==> compileDebugKotlin"
./gradlew "${GRADLE_ARGS[@]}" :app:compileDebugKotlin --rerun-tasks

echo "==> lintDebug"
./gradlew "${GRADLE_ARGS[@]}" :app:lintDebug

echo "==> testDebugUnitTest"
./gradlew "${GRADLE_ARGS[@]}" :app:testDebugUnitTest

echo "==> all checks passed"
