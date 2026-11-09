#!/bin/sh
# Builds the static seedmap helper around cubiomes (MIT, cloned into ./cubiomes on first run).
# Output: ./seedmap-linux-x64 - deploy-library.sh installs it as /opt/velo/library/data/bin/seedmap.
set -e
cd "$(dirname "$0")"
[ -d cubiomes ] || git clone -q --depth 1 https://github.com/Cubitect/cubiomes.git cubiomes
gcc -O2 -static -fwrapv -I cubiomes seedmap.c $(ls cubiomes/*.c | grep -v tests.c) -lm -o seedmap-linux-x64
strip seedmap-linux-x64
