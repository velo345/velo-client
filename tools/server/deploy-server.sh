#!/usr/bin/env bash
# Deploys the Velo server (jar), its nginx config, the host firewall and the share page script to the production box,
# keeping a backup of what was there. Safe to run again. Clients are NOT released by this.
#
#   tools/server/deploy-server.sh            -> velo-new (the ssh alias in ~/.ssh/config)
#   tools/server/deploy-server.sh user@host
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
TARGET="${1:-velo-new}"

./gradlew :server:shadowJar -q
scp -q server/build/libs/velo-server.jar tools/server/nginx/velo.conf tools/server/host/firewall.nft velo-site/assets/clip.js "$TARGET:/tmp/"
ssh "$TARGET" 'bash -s' <<'REMOTE'
set -euo pipefail
stamp=$(date +%F-%H%M%S)
mkdir -p /opt/velo/backups
cp /opt/velo/server/velo-server.jar "/opt/velo/backups/velo-server.jar.$stamp"
cp /etc/nginx/conf.d/velo.conf "/opt/velo/backups/velo.conf.$stamp"
cp /etc/velo/firewall.nft "/opt/velo/backups/firewall.nft.$stamp" 2>/dev/null || true

install -m 640 -o root -g velo /tmp/velo-server.jar /opt/velo/server/velo-server.jar
install -m 644 /tmp/velo.conf /etc/nginx/conf.d/velo.conf
if nginx -t 2>/dev/null; then
	systemctl reload nginx
else
	echo "nginx config invalid - putting the old one back"
	cp "/opt/velo/backups/velo.conf.$stamp" /etc/nginx/conf.d/velo.conf
	nginx -t && systemctl reload nginx
fi
# World Hosting relay port (25600/tcp) - only our own table is replaced, never Docker's rules.
install -m 600 /tmp/firewall.nft /etc/velo/firewall.nft
systemctl restart velo-firewall
install -d /var/www/velo-client.com/assets
install -m 664 /tmp/clip.js /var/www/velo-client.com/assets/clip.js
chgrp velo /var/www/velo-client.com/assets/clip.js 2>/dev/null || true

systemctl restart velo-server
for i in $(seq 1 20); do
	curl -fsS http://127.0.0.1:8787/v1/health >/dev/null 2>&1 && break
	sleep 1
done
echo "health:   $(curl -fsS http://127.0.0.1:8787/v1/health)"
echo "partners: $(curl -fsS http://127.0.0.1:8787/v1/partners)"
echo "worlds:   $(curl -fsS http://127.0.0.1:8787/v1/worlds/public)"
ss -ltn | grep -q ':25600 ' && echo "relay:    listening on 25600" || echo "relay:    NOT listening"
tail -n 5 /var/log/nginx/error.log 2>/dev/null || true
rm -f /tmp/velo-server.jar /tmp/velo.conf /tmp/firewall.nft /tmp/clip.js
REMOTE
echo "Deployed. Backups are in /opt/velo/backups on $TARGET."
