#!/bin/sh
# Velo YouTrack container (hardened): no host ports except loopback 8180, non-root, no capabilities, limits.
set -e
docker rm -f youtrack >/dev/null 2>&1 || true
docker run -d --name youtrack --restart unless-stopped \
  --network proxy --user 13001:13001 \
  --security-opt no-new-privileges:true --cap-drop ALL \
  --memory 4g --memory-swap 4g --pids-limit 512 --cpus 2 \
  --log-opt max-size=20m --log-opt max-file=3 \
  --env-file /etc/velo/youtrack.env \
  -p 127.0.0.1:8180:8080 \
  -v /opt/youtrack/data:/opt/youtrack/data -v /opt/youtrack/conf:/opt/youtrack/conf \
  -v /opt/youtrack/logs:/opt/youtrack/logs -v /opt/youtrack/backups:/opt/youtrack/backups \
  jetbrains/youtrack:2026.3.20641 >/dev/null
c=0
for i in $(seq 1 50); do c=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:8180/api/config?fields=version" || true); [ "$c" = 200 ] && break; sleep 3; done
echo "health $c"
docker ps --filter name=youtrack --format "{{.Status}} {{.Ports}}"
