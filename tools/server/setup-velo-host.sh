#!/usr/bin/env bash
# One-time (and re-runnable) setup of the Velo services on the shared Debian 12 server - run as root from the folder
# holding this repo's tools/server files:  sudo bash setup-velo-host.sh
#
# What it does (and doesn't touch):
#  - Java 21 (Temurin), nginx (bound only to 127.0.0.1 and the Docker bridge - ports 80/443 stay with FlipSMP's
#    Caddy), fail2ban for SSH, unattended security updates.
#  - Users velo-server / velo-bot / velo-panel in group "velo"; /opt/velo/{server,bot,panel,backups}; /etc/velo.
#  - systemd units for the server, the Discord bot, the owner panel, nightly backups and the host firewall.
#  - The firewall is installed but NOT switched on here: tools/server/enable-firewall.sh does that with an
#    automatic rollback, so a mistake can't lock anyone out of SSH.
# It never touches Docker, the containers, or anything outside the paths above.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }
export DEBIAN_FRONTEND=noninteractive

echo "== packages"
apt-get update -qq
apt-get install -y -qq ca-certificates gnupg wget apt-transport-https sqlite3 >/dev/null
if [ ! -f /etc/apt/keyrings/adoptium.gpg ]; then
	install -d -m 755 /etc/apt/keyrings
	wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
	echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" > /etc/apt/sources.list.d/adoptium.list
	apt-get update -qq
fi
apt-get install -y -qq temurin-21-jre >/dev/null
# nginx must not start with Debian's default site (port 80 is Caddy's): block service starts while installing.
if ! dpkg -s nginx >/dev/null 2>&1; then
	printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
	chmod 755 /usr/sbin/policy-rc.d
	apt-get install -y -qq nginx >/dev/null || true
	rm -f /usr/sbin/policy-rc.d
fi
rm -f /etc/nginx/sites-enabled/default
apt-get install -y -qq fail2ban unattended-upgrades >/dev/null
java -version 2>&1 | head -1

echo "== users and folders"
getent group velo >/dev/null || groupadd --system velo
for u in velo-server velo-bot velo-panel; do
	id "$u" >/dev/null 2>&1 || useradd --system --gid velo --home-dir /nonexistent --shell /usr/sbin/nologin "$u"
done
usermod -aG systemd-journal,adm velo-panel
install -d -m 755 -o root -g root /opt/velo
install -d -m 2750 -o velo-server -g velo /opt/velo/server
install -d -m 2770 -o velo-server -g velo /opt/velo/server/data
install -d -m 2750 -o velo-bot -g velo /opt/velo/bot
install -d -m 2770 -o velo-bot -g velo /opt/velo/bot/bot-data
install -d -m 2750 -o root -g velo /opt/velo/panel
install -d -m 2750 -o root -g velo /opt/velo/backups
install -d -m 750 -o velo-panel -g velo /var/lib/velo-panel
install -d -m 750 -o root -g velo /etc/velo
# Only the panel may read its accounts (password hashes + TOTP secrets): dir root 711, file velo-panel 400.
install -d -m 711 -o root -g root /etc/velo-panel
install -d -m 2775 -o root -g velo /var/www/velo-client.com

echo "== nginx"
install -m 644 "$HERE/nginx/velo.conf" /etc/nginx/conf.d/velo.conf
install -d /etc/nginx/snippets
install -m 644 "$HERE"/nginx/snippets/velo-*.conf /etc/nginx/snippets/
nginx -t
systemctl enable --now nginx >/dev/null
systemctl reload nginx

echo "== services"
install -m 644 "$HERE"/systemd/velo-*.service "$HERE"/systemd/velo-*.timer /etc/systemd/system/
install -m 750 -o root -g root "$HERE/host/velo-backup" /usr/local/sbin/velo-backup
install -m 750 -o root -g root "$HERE/host/velo-panel-adduser" /usr/local/sbin/velo-panel-adduser
install -m 640 -o root -g root "$HERE/host/firewall.nft" /etc/velo/firewall.nft
visudo -cf "$HERE/host/sudoers-velo-panel"
install -m 440 -o root -g root "$HERE/host/sudoers-velo-panel" /etc/sudoers.d/velo-panel
systemctl daemon-reload
systemctl enable velo-backup.timer >/dev/null
systemctl start velo-backup.timer

echo "== fail2ban + security updates"
install -m 644 "$HERE/host/fail2ban-velo.local" /etc/fail2ban/jail.d/velo.local
systemctl enable --now fail2ban >/dev/null
systemctl restart fail2ban
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOT'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOT
systemctl enable --now unattended-upgrades >/dev/null

echo "== done (velo-server, velo-bot and velo-panel are installed but started separately)"
