#!/usr/bin/env bash
# Points packaging/aur/PKGBUILD at a released version: sets pkgver, resets pkgrel, puts the real sha256 of the
# release's linux-x64.tar.gz in, and writes .SRCINFO (no makepkg needed - this isn't an Arch box).
# Run after CI attached the tarball to the release:   tools/release/update_aur.sh 1.1.3
# Then publish to the AUR (once: create the package on aur.archlinux.org with your SSH key):
#   git clone ssh://aur@aur.archlinux.org/velo-client-launcher-bin.git /tmp/aur-velo
#   cp packaging/aur/PKGBUILD packaging/aur/.SRCINFO /tmp/aur-velo/ && cd /tmp/aur-velo && git commit -am "1.1.3" && git push
set -euo pipefail
version="${1:?usage: update_aur.sh <version>}"
here="$(cd "$(dirname "$0")/../.." && pwd)"
dir="$here/packaging/aur"
asset="velo-client-launcher-${version}-linux-x64.tar.gz"
url="https://github.com/velo345/velo-client/releases/download/v${version}/${asset}"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
echo "Downloading $url"
curl -fsSL -o "$tmp/$asset" "$url"
sum="$(sha256sum "$tmp/$asset" | cut -d' ' -f1)"
# The folder inside must be velo-client-launcher/ (what package() copies).
tar -tzf "$tmp/$asset" | head -1 | grep -q '^velo-client-launcher/' || { echo "tarball has no velo-client-launcher/ folder" >&2; exit 1; }

sed -i -E "s/^pkgver=.*/pkgver=${version}/; s/^pkgrel=.*/pkgrel=1/; s/^sha256sums=.*/sha256sums=('${sum}')/" "$dir/PKGBUILD"

# .SRCINFO from the PKGBUILD's fields (same layout makepkg --printsrcinfo writes).
(
	cd "$dir"
	# shellcheck disable=SC1091
	source ./PKGBUILD
	{
		echo "pkgbase = ${pkgname}"
		echo "	pkgdesc = ${pkgdesc}"
		echo "	pkgver = ${pkgver}"
		echo "	pkgrel = ${pkgrel}"
		echo "	url = ${url}"
		for a in "${arch[@]}"; do echo "	arch = $a"; done
		for l in "${license[@]}"; do echo "	license = $l"; done
		for d in "${depends[@]}"; do echo "	depends = $d"; done
		for d in "${optdepends[@]}"; do echo "	optdepends = $d"; done
		for p in "${provides[@]}"; do echo "	provides = $p"; done
		for c in "${conflicts[@]}"; do echo "	conflicts = $c"; done
		for o in "${options[@]}"; do echo "	options = $o"; done
		for s in "${source[@]}"; do echo "	source = $s"; done
		for s in "${sha256sums[@]}"; do echo "	sha256sums = $s"; done
		echo
		echo "pkgname = ${pkgname}"
	} > .SRCINFO
)
echo "PKGBUILD + .SRCINFO now at ${version} (sha256 ${sum})"
