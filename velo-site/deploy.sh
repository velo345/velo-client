#!/usr/bin/env bash
# Builds the site and uploads it to the server's /var/www/velo-client.com (nginx serves it behind Caddy; see
# tools/server/setup-velo-host.sh), then tells search engines about the pages through IndexNow.
#
#   velo-site/deploy.sh                    build + upload to velo-brand (the ssh alias of the production server)
#   velo-site/deploy.sh user@host          another server
#   velo-site/deploy.sh velo-brand --dry-run show what would change
#
# rsync deletes files that are gone from dist/ - the folder only ever holds the build.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
TARGET="${1:-velo-brand}"
DRY=()
[[ "${2:-}" == "--dry-run" ]] && DRY=(--dry-run)
REMOTE_DIR="${REMOTE_DIR:-/var/www/velo-client.com}"

node build.js

# Every inline <script> is allowed by its sha256 in the site CSP (tools/server/nginx/snippets/velo-site-csp.conf):
# rewrite the list from what the build produced and push it to nginx when it changed (before the pages that need it).
CSP=../tools/server/nginx/snippets/velo-site-csp.conf
if python3 - "$CSP" <<'PY'
import re, sys, glob, hashlib, base64
path = sys.argv[1]
hs = set()
for f in glob.glob("dist/**/*.html", recursive=True):
    for m in re.finditer(r"<script>([\s\S]*?)</script>", open(f, encoding="utf-8").read()):
        hs.add("'sha256-" + base64.b64encode(hashlib.sha256(m.group(1).encode()).digest()).decode() + "'")
old = open(path).read()
new = re.sub(r"script-src 'self'[^;]*;", "script-src 'self'" + "".join(" " + h for h in sorted(hs)) + ";", old)
if new == old:
    sys.exit(1)
open(path, "w").write(new)
PY
then
	if [[ ${#DRY[@]} -eq 0 ]]; then
		scp -q "$CSP" "$TARGET:/tmp/velo-site-csp.conf"
		ssh "$TARGET" "cp /etc/nginx/snippets/velo-site-csp.conf /root/velo-site-csp.conf.bak && cp /tmp/velo-site-csp.conf /etc/nginx/snippets/velo-site-csp.conf && nginx -t -q && systemctl reload nginx || { cp /root/velo-site-csp.conf.bak /etc/nginx/snippets/velo-site-csp.conf; echo 'CSP update failed - restored'; exit 1; }"
		echo "CSP: inline script hashes updated"
	fi
fi
rsync -rlptz --delete --exclude=/dl/ --chmod=D2775,F664 "${DRY[@]}" dist/ "$TARGET:$REMOTE_DIR/"
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
