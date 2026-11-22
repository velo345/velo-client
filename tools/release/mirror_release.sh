#!/usr/bin/env bash
# mirror_release.sh <version> [ssh target]: copies a GitHub release onto the Velo server so downloads keep working when
# GitHub's release downloads time out (happens for signed-out users now and then).
#   - every asset            -> /var/www/velo-client.com/dl/v<version>/<file>   (https://velo-client.com/dl/...)
#   - dl/releases.json       -> GitHub's release JSON for the mirrored versions, download URLs pointing at the mirror;
#                               the launcher updater reads it when api.github.com doesn't answer (UpdateService.MIRROR)
# The website's download buttons use the mirror directly (velo-site/site.config.json links.downloadMirror).
# Run it after every release (after the CI assets are up). Uses the gh CLI (signed in), so it works during GitHub outages
# of anonymous downloads too.
set -euo pipefail
VERSION="${1:?version, e.g. 1.2.6}"; TARGET="${2:-velo-brand}"
REPO=velo345/velo-client; ROOT=/var/www/velo-client.com/dl; LOCAL="${MIRROR_CACHE:-$HOME/.cache/velo-dl}"
mkdir -p "$LOCAL"
# The server pulls every asset from GitHub itself (~16 MB/s there vs. hours of upload from a home line - the old
# local download + rsync broke off silently more than once and the website buttons 404'd). Name + size from gh; each
# file lands as .<name>.part and is only renamed once its size matches, so nginx never serves half a file.
gh release view "v$VERSION" --repo "$REPO" --json assets -q '.assets[] | "v'"$VERSION"' \(.name) \(.size)"' > "$LOCAL/assets-$VERSION.txt"
# the 1.8.9 mod stays on its own version (site.config release.legacyMod): carry it into every mirrored version folder
LEGACY=$(node -e 'process.stdout.write(require(process.argv[1]).release.legacyMod||"")' "$(realpath "$(dirname "$0")/../../velo-site/site.config.json")")
if [[ -n "$LEGACY" ]] && ! grep -q " $LEGACY " "$LOCAL/assets-$VERSION.txt"; then
  gh release list --repo "$REPO" --limit 30 --json tagName -q '.[].tagName' | while read -r t; do
    line=$(gh release view "$t" --repo "$REPO" --json assets -q '.assets[] | "'"$t"' \(.name) \(.size)"' | grep -F " $LEGACY " || true)
    [[ -n "$line" ]] && { echo "$line" >> "$LOCAL/assets-$VERSION.txt"; break; }; done
fi
scp -q "$LOCAL/assets-$VERSION.txt" "$TARGET:/tmp/velo-mirror-assets.txt"
ssh "$TARGET" "mkdir -p '$ROOT/v$VERSION' && cd '$ROOT/v$VERSION' && fail=0 && while read -r tag n s; do
  [ -f \"\$n\" ] && [ \"\$(stat -c%s \"\$n\")\" = \"\$s\" ] && continue
  url=\"https://github.com/$REPO/releases/download/\$tag/\$(printf %s \"\$n\" | sed 's/+/%2B/g')\"
  if curl -sfL --retry 3 -o \".\$n.part\" \"\$url\" && [ \"\$(stat -c%s \".\$n.part\")\" = \"\$s\" ]; then
    chmod 664 \".\$n.part\" && mv \".\$n.part\" \"\$n\" && echo \"  mirrored \$n\"
  else rm -f \".\$n.part\"; echo \"  FAILED \$n\"; fail=1; fi
done < /tmp/velo-mirror-assets.txt; chgrp -R velo . 2>/dev/null; exit \$fail"
# Only the newest 3 releases stay mirrored (each is ~2.6 GB; the launcher only ever downloads the latest)
ssh "$TARGET" "cd '$ROOT' && ls -1d v*/ 2>/dev/null | sed 's#/##' | sort -V | head -n -3 | xargs -r rm -rf --"
# releases.json: every version folder on the server, newest first
MIRRORED=$(ssh "$TARGET" "ls -1 '$ROOT'" | sed -n 's/^v//p')
gh api "repos/$REPO/releases?per_page=30" > "$LOCAL/github-releases.json"
python3 - "$LOCAL/github-releases.json" "$LOCAL/releases.json" $MIRRORED <<'PY'
import json, sys, urllib.parse
src, out, versions = sys.argv[1], sys.argv[2], set(sys.argv[3:])
keep = []
for r in json.load(open(src)):
    if r["draft"] or r["tag_name"].lstrip("v") not in versions: continue
    for a in r["assets"]:
        a["browser_download_url"] = f"https://velo-client.com/dl/{r['tag_name']}/{urllib.parse.quote(a['name'])}"
    keep.append({k: r[k] for k in ("tag_name", "name", "draft", "prerelease", "body", "html_url", "published_at", "assets")})
json.dump(keep, open(out, "w")); print("releases.json:", [r["tag_name"] for r in keep])
PY
rsync -tz --chmod=F664 "$LOCAL/releases.json" "$TARGET:$ROOT/releases.json"
ssh "$TARGET" "chgrp -R velo '$ROOT' 2>/dev/null || true"
for f in releases.json "v$VERSION/$(ls "$LOCAL/v$VERSION" | grep -m1 '\.exe$')"; do
  curl -s -o /dev/null -r 0-99 -w "https://velo-client.com/dl/$f -> %{http_code}\n" "https://velo-client.com/dl/$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "$f")"
done
