#!/bin/bash
# Moves the live Velo services from one host to another with ~15 s of API downtime and no split data:
#   1. stop server, bot and panel on the old host (nothing writes any more)
#   2. final rsync old -> new (server data, bot data, panel state, websites) - the new host pulls over SSH (agent forwarding)
#   3. start everything on the new host
#   4. the old host's nginx forwards every Velo host to the new one (tools/server/nginx/velo-forward.conf) and a socat
#      unit forwards the World Hosting relay port - players whose DNS still says "old" land on the new host anyway
# Then point DNS at the new host. Rollback: on the new host stop the services; on the old host put
# /root/velo.conf.pre-move back to /etc/nginx/conf.d/velo.conf, restart nginx, stop velo-relay-forward, start the services.
#
#   tools/server/cutover.sh velo-new velo-brand 62.129.150.119 62.129.150.101
# (ssh aliases for old/new, then their public IPs). Needs the deploy key in ssh-agent (ssh-add ~/.ssh/velo_deploy).
set -euo pipefail
OLD=$1 NEW=$2 OLD_IP=$3 NEW_IP=$4
HERE=$(cd "$(dirname "$0")" && pwd)

echo "== 1. stopping on $OLD"
ssh "$OLD" 'systemctl stop velo-server velo-bot velo-panel'
ssh "$NEW" 'systemctl stop velo-server velo-panel velo-bot 2>/dev/null || true'

echo "== 2. final sync $OLD -> $NEW"
ssh -A "$NEW" "set -e
R='rsync -a --delete -e \"ssh -o StrictHostKeyChecking=accept-new\"'
eval \$R root@$OLD_IP:/opt/velo/server/data/ /opt/velo/server/data/
eval \$R root@$OLD_IP:/opt/velo/bot/bot-data/ /opt/velo/bot/bot-data/
eval \$R root@$OLD_IP:/var/lib/velo-panel/ /var/lib/velo-panel/
eval \$R root@$OLD_IP:/var/www/ /var/www/
chown -R velo-server:velo /opt/velo/server/data
chown -R velo-bot:velo /opt/velo/bot/bot-data
du -sh /opt/velo/server/data"

echo "== 3. starting on $NEW"
ssh "$NEW" 'systemctl start velo-server velo-panel && systemctl enable --now velo-bot && sleep 5 && systemctl is-active velo-server velo-panel velo-bot'
curl -sf -o /dev/null -w "new host /v1/health %{http_code}\n" --resolve "api.velo-client.com:443:$NEW_IP" https://api.velo-client.com/v1/health

echo "== 4. $OLD forwards to $NEW"
scp -q "$HERE/nginx/velo-forward.conf" "$OLD:/root/velo-forward.conf"
ssh "$OLD" "set -e
cp /etc/nginx/conf.d/velo.conf /root/velo.conf.pre-move
cp /root/velo-forward.conf /etc/nginx/conf.d/velo.conf
nginx -t
systemctl restart nginx
sleep 1
tail -n 3 /var/log/nginx/error.log | grep -i emerg && exit 1 || true
systemctl disable velo-server velo-bot velo-panel velo-backup.timer >/dev/null 2>&1 || true
systemctl stop velo-backup.timer || true
systemd-run --unit velo-relay-forward --property=Restart=always /usr/bin/socat TCP-LISTEN:25600,fork,reuseaddr TCP:$NEW_IP:25600
sleep 1; systemctl is-active velo-relay-forward"
curl -sf -o /dev/null -w "via old host /v1/health %{http_code}\n" --resolve "api.velo-client.com:443:$OLD_IP" https://api.velo-client.com/v1/health
curl -sf -o /dev/null -w "via old host / %{http_code}\n" --resolve "velo-client.com:443:$OLD_IP" https://velo-client.com/
echo "Done. Now point the DNS A records at $NEW_IP."
