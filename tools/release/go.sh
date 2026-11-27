#!/usr/bin/env bash
# One-command stages of a normal release (written for 1.6.0, works for any version in stonecutter.properties.toml).
# Everything that can be prepared is already prepared: production jars, server jar, release notes (news/posts/<v>/),
# trailer upload. Then, on "go":
#
#   tools/release/go.sh check      verify that everything is in place (touches nothing)
#   tools/release/go.sh release    tag v<v>, push the tag (CI builds the installers) and create the GitHub release with
#                                  the jars + server jar + legacy mod (the CI attaches .exe/.deb/.tar.gz/.dmg later)
#   tools/release/go.sh ci         wait for the installer CI and list what it attached
#   tools/release/go.sh mirror     copy every asset to the Velo server (dl/v<v>/) + verify the download links
#   tools/release/go.sh addons     publish the Velo+ add-ons (private repo) and sync the server
#   tools/release/go.sh site       bump velo-site/site.config.json, deploy the website, commit + push the bump
#
# Owner posts: news/posts/<v>/ (import in the launcher's News editor) and news/posts/<v>/discord.md.
# NEVER attach velo-addons jars to the public release. No Co-Authored-By in commits (see memory).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
V=$(grep -E '^mod\.version' stonecutter.properties.toml | sed -E 's/.*"([^"]+)".*/\1/')
REPO=velo345/velo-client
TAG="v$V"
NOTES="news/posts/$V/github-release.md"
LEGACY="$HOME/.cache/velo-dl/v1.5.1/velo-client-1.2.0+mc1.8.9.jar"
SERVER_JAR=$(ls server/build/libs/*.jar 2>/dev/null | grep -v -- "-plain" | head -1 || true)
VERSIONS=(1.21.1 1.21.10 1.21.11 26.1 26.2 26.3)

jars() { for v in "${VERSIONS[@]}"; do echo "versions/$v/build/libs/velo-client-$V+$v.jar"; done; }

check() {
	local bad=0
	for j in $(jars); do [[ -f "$j" ]] || { echo "MISSING $j"; bad=1; }; done
	[[ -f "$LEGACY" ]] || { echo "MISSING legacy mod $LEGACY"; bad=1; }
	[[ -n "$SERVER_JAR" && -f "$SERVER_JAR" ]] || { echo "MISSING server jar (./gradlew :server:shadowJar)"; bad=1; }
	[[ -f "$NOTES" ]] || { echo "MISSING $NOTES"; bad=1; }
	[[ -f "news/posts/$V/post.json" && -f "news/posts/$V/discord.md" ]] || { echo "MISSING news bundle"; bad=1; }
	if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then echo "TAG $TAG already exists on origin"; bad=1; fi
	git fetch -q origin
	[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "HEAD is not origin/main (push first)"; bad=1; }
	[[ -z "$(git status --porcelain --untracked-files=no)" ]] || { echo "tracked files have uncommitted changes:"; git status --short --untracked-files=no; bad=1; }
	curl -sfI https://velo-client.com/dl/media/velo-1-6-release.mp4 >/dev/null || { echo "trailer link is down"; bad=1; }
	for u in https://api.velo-client.com/v1/update-policy; do curl -sf "$u" >/dev/null || { echo "$u is down"; bad=1; }; done
	grep -q "velo-1-6-release.mp4" "news/posts/$V/discord.md" || { echo "discord.md has no trailer link"; bad=1; }
	[[ $bad == 0 ]] && echo "all in place for $TAG" || { echo "NOT READY"; return 1; }
}

release() {
	check
	git tag "$TAG"
	git push origin "$TAG"
	local assets=($(jars) "$LEGACY" "$SERVER_JAR")
	gh release create "$TAG" --repo "$REPO" --latest --title "Velo Client $V" --notes-file "$NOTES" "${assets[@]}"
	echo "release created; CI is attaching the installers (10-20 min): tools/release/go.sh ci"
}

ci() {
	local id
	id=$(gh run list --repo "$REPO" --workflow "Package Launcher" --limit 1 --json databaseId -q '.[0].databaseId')
	gh run watch "$id" --repo "$REPO" --exit-status
	gh release view "$TAG" --repo "$REPO" --json assets -q '.assets[] | "\(.size)\t\(.name)"'
}

mirror() {
	tools/release/mirror_release.sh "$V"
	for f in "Velo.Client.Launcher-$V.exe" "velo-client-launcher_${V}_amd64.deb" "velo-client-launcher-$V-linux-x64.tar.gz" "velo-client-$V+26.2.jar"; do
		code=$(curl -s -o /dev/null -w "%{http_code}" -I "https://velo-client.com/dl/$TAG/$(printf %s "$f" | sed 's/+/%2B/g')")
		echo "$code $f"
	done
	curl -s https://velo-client.com/dl/releases.json | grep -c "\"$TAG\"" | sed 's/^/releases.json mentions the tag: /'
}

addons() { python3 tools/release/publish_addons.py; }

site() {
	python3 - "$V" <<'PY'
import json, sys, datetime
v = sys.argv[1]
p = "velo-site/site.config.json"
d = json.load(open(p))
r = d["release"]
today = datetime.date.today()
r["version"] = v
r["launcherExe"] = f"Velo.Client.Launcher-{v}.exe"
r["launcherDeb"] = f"velo-client-launcher_{v}_amd64.deb"
r["launcherTar"] = f"velo-client-launcher-{v}-linux-x64.tar.gz"
r["launcherDmgArm"] = f"velo-client-launcher-{v}-macos-arm64.dmg"
r["launcherDmgX64"] = f"velo-client-launcher-{v}-macos-x64.dmg"
r["date"] = today.isoformat()
r["datePretty"] = today.strftime("%b %-d, %Y")
json.dump(d, open(p, "w"), indent=2, ensure_ascii=False)
open(p, "a").write("\n")
print("site.config.json ->", v)
PY
	velo-site/deploy.sh velo-brand
	git add velo-site/site.config.json
	git commit -q -m "Website: Velo Client $V download links"
	git push origin main
}

case "${1:-check}" in
	check) check ;;
	release) release ;;
	ci) ci ;;
	mirror) mirror ;;
	addons) addons ;;
	site) site ;;
	*) sed -n 2,16p "$0"; exit 1 ;;
esac
