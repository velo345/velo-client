#!/usr/bin/env bash
# Starts the DEV launcher: it bundles the dev client jars (with the unreleased features from src/dev), keeps its own
# profiles in ~/.velo-client-dev (signed in with a copy of your account) and never updates itself.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec ./gradlew :launcher:run -PveloDev=true -q --console=plain
