#!/usr/bin/env bash
# Builds the site and uploads it to the server's /var/www/velo-client.com (nginx serves it behind Caddy; see
# tools/server/setup-velo-host.sh), then tells search engines about the pages through IndexNow.
#
#   velo-site/deploy.sh                    build + upload to velo-new (the ssh alias of the production server)
#   velo-site/deploy.sh user@host          another server
#   velo-site/deploy.sh velo-new --dry-run show what would change
#
# rsync deletes files that are gone from dist/ - the folder only ever holds the build.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
TARGET="${1:-velo-new}"
DRY=()
[[ "${2:-}" == "--dry-run" ]] && DRY=(--dry-run)
REMOTE_DIR="${REMOTE_DIR:-/var/www/velo-client.com}"

node build.js
rsync -rlptz --delete --chmod=D2775,F664 "${DRY[@]}" dist/ "$TARGET:$REMOTE_DIR/"
[[ ${#DRY[@]} -gt 0 ]] && exit 0
ssh "$TARGET" "chgrp -R velo '$REMOTE_DIR' 2>/dev/null || true"
echo "Deployed to https://velo-client.com"

# IndexNow (Bing, Yandex, Seznam, Naver...): one ping with every page; Google reads sitemap.xml on its own.
KEY=$(node -e 'const c=require("./site.config.json");process.stdout.write((c.seo&&c.seo.indexNowKey)||"")')
if [[ -n "$KEY" ]]; then
	URLS=$(node -e 'const fs=require("fs");process.stdout.write(JSON.stringify(fs.readFileSync("dist/urls.txt","utf8").trim().split("\n")))')
	curl -s -o /dev/null -w "IndexNow: HTTP %{http_code}\n" -X POST https://api.indexnow.org/indexnow -H "Content-Type: application/json; charset=utf-8" \
		-d "{\"host\":\"velo-client.com\",\"key\":\"$KEY\",\"keyLocation\":\"https://velo-client.com/$KEY.txt\",\"urlList\":$URLS}" || true
fi
