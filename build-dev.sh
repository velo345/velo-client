#!/usr/bin/env bash
# Builds the DEV client jars: they include the unreleased features from src/dev and are versioned "<version>-dev".
# Production builds (build-and-deploy.sh, releases) never contain src/dev. The dev launcher (./launch-launcher-dev.sh)
# bundles these jars, keeps its own data folder (~/.velo-client-dev) and never updates itself.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
./gradlew :1.21.11:build :26.1:build :26.2:build :26.3:build -PveloDev=true -q --console=plain
echo "Dev client jars: $(ls versions/*/build/libs/velo-client-*-dev+*.jar | grep -v sources | tr '\n' ' ')"
