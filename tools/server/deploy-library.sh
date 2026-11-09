#!/usr/bin/env bash
# Deploys the Velo Library (library/ -> velo-library.jar) as its own systemd service next to velo-server, plus the nginx
# pieces that front it (limits, cache, /packs). Safe to run again; keeps backups; never restarts velo-server.
#
#   tools/server/deploy-library.sh             -> velo-brand
#   tools/server/deploy-library.sh user@host
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
TARGET="${1:-velo-brand}"

./gradlew :library:shadowJar -q
# the seed map helper (cubiomes, static binary - the server has no compiler)
library/native/build.sh
scp -q library/native/seedmap-linux-x64 library/build/libs/velo-library.jar tools/server/systemd/velo-library.service tools/server/nginx/velo.conf \
	tools/server/nginx/snippets/velo-library.conf tools/server/nginx/snippets/velo-library-proxy.conf "$TARGET:/tmp/"
ssh "$TARGET" 'bash -s' <<'REMOTE'
set -euo pipefail
stamp=$(date +%F-%H%M%S)
mkdir -p /opt/velo/backups
id velo-library >/dev/null 2>&1 || useradd --system --gid velo --home-dir /nonexistent --shell /usr/sbin/nologin velo-library
install -d -m 755 -o root -g velo /opt/velo/library
# data: other users may pass through (nginx serves data/packs) but not list or read anything else
install -d -m 2751 -o velo-library -g velo /opt/velo/library/data
install -d -m 755 -o velo-library -g velo /opt/velo/library/data/packs
install -d -m 700 -o www-data -g root /var/cache/nginx/velo-library
[ -f /opt/velo/library/velo-library.jar ] && cp /opt/velo/library/velo-library.jar "/opt/velo/backups/velo-library.jar.$stamp"
install -m 640 -o root -g velo /tmp/velo-library.jar /opt/velo/library/velo-library.jar
install -d -m 750 -o velo-library -g velo /opt/velo/library/data/bin
install -m 750 -o root -g velo /tmp/seedmap-linux-x64 /opt/velo/library/data/bin/seedmap
if [ ! -f /etc/velo/library.env ]; then
	owners=$(grep -E '^VELO_OWNERS=' /etc/velo/server.env 2>/dev/null | cut -d= -f2- || true)
	printf 'LIBRARY_OWNERS=%s\nLIBRARY_ADMIN_TOKEN=%s\n' "$owners" "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" > /etc/velo/library.env
	chown root:velo /etc/velo/library.env
	chmod 640 /etc/velo/library.env
fi
install -m 644 /tmp/velo-library.service /etc/systemd/system/velo-library.service
systemctl daemon-reload
systemctl enable --now velo-library >/dev/null 2>&1 || true
systemctl restart velo-library

cp /etc/nginx/conf.d/velo.conf "/opt/velo/backups/velo.conf.$stamp"
for f in velo-library.conf velo-library-proxy.conf; do
	[ -f "/etc/nginx/snippets/$f" ] && cp "/etc/nginx/snippets/$f" "/opt/velo/backups/$f.$stamp"
	install -m 644 "/tmp/$f" "/etc/nginx/snippets/$f"
done
install -m 644 /tmp/velo.conf /etc/nginx/conf.d/velo.conf
if nginx -t 2>/dev/null; then
	# new limit zones need a restart, not a reload (see README gotchas)
	systemctl restart nginx
else
	echo "nginx config invalid - putting the old one back"
	nginx -t || true
	cp "/opt/velo/backups/velo.conf.$stamp" /etc/nginx/conf.d/velo.conf
	nginx -t && systemctl reload nginx
	exit 1
fi
for i in $(seq 1 30); do
	curl -fsS http://127.0.0.1:8791/v2/health >/dev/null 2>&1 && break
	sleep 1
done
echo "library:  $(curl -sS http://127.0.0.1:8791/v2/health)"
echo "velo:     $(curl -sS http://127.0.0.1:8787/v1/health)"
systemctl show velo-library -p MemoryCurrent -p CPUQuotaPerSecUSec | tr '\n' ' '; echo
rm -f /tmp/seedmap-linux-x64 /tmp/velo-library.jar /tmp/velo-library.service /tmp/velo.conf /tmp/velo-library.conf /tmp/velo-library-proxy.conf
REMOTE
echo "Library deployed. Kill switch: tools/server/library-switch.sh off|on [render|ping|heavy|write|request]"
