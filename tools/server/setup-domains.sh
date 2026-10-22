#!/usr/bin/env bash
# One-time setup of the velo-client.com domains on the server (Ubuntu/Debian, run from the repo checkout):
#
#   sudo tools/server/setup-domains.sh [--with-legacy]
#
# - installs nginx and certbot (python3-certbot-nginx) if missing
# - installs tools/server/nginx/{velo-client.com,api.velo-client.com}.conf as nginx sites and enables them
# - creates /var/www/velo-client.com for the website (filled by velo-site/deploy.sh)
# - gets Let's Encrypt certificates for velo-client.com, www.velo-client.com and api.velo-client.com
#   (certbot --nginx; renewals run from certbot's own timer)
# - sets VELO_PUBLIC_URL=https://api.velo-client.com on velo-server.service (Tebex checkout returns, ad postbacks)
#
# --with-legacy also serves the server on client.asteriasmp.net (needs that DNS name pointing here), for clients
# older than 0.9.8 until AsteriaSMP's domain goes away.
#
# Before running: A (and AAAA, if the box has IPv6) records for velo-client.com, www.velo-client.com and
# api.velo-client.com must point at this machine - certbot checks them over http.
set -euo pipefail

say() { printf '\033[1;31m[velo-domains]\033[0m %s\n' "$*"; }
[[ $EUID -eq 0 ]] || { say "Run with sudo."; exit 1; }
cd "$(dirname "${BASH_SOURCE[0]}")"

LEGACY=0
[[ "${1:-}" == "--with-legacy" ]] && LEGACY=1
EMAIL="${CERTBOT_EMAIL:-}"
SERVER_UNIT="${SERVER_UNIT:-velo-server}"

if ! command -v nginx >/dev/null || ! command -v certbot >/dev/null; then
	say "Installing nginx and certbot"
	apt-get update -q
	apt-get install -y -q nginx certbot python3-certbot-nginx
fi

for ip_name in velo-client.com www.velo-client.com api.velo-client.com; do
	if ! getent hosts "$ip_name" >/dev/null; then
		say "DNS for $ip_name doesn't resolve yet - set the A record, wait for it, then run this again."
		exit 1
	fi
done

say "Installing the nginx sites"
install -m 644 nginx/velo-client.com.conf /etc/nginx/sites-available/velo-client.com
install -m 644 nginx/api.velo-client.com.conf /etc/nginx/sites-available/api.velo-client.com
if [[ $LEGACY == 1 ]]; then
	sed -i 's/server_name api.velo-client.com;/server_name api.velo-client.com client.asteriasmp.net;/' /etc/nginx/sites-available/api.velo-client.com
	# The old site file served the same thing; drop it so the two don't fight over the name.
	rm -f /etc/nginx/sites-enabled/client.asteriasmp.net
fi
ln -sf /etc/nginx/sites-available/velo-client.com /etc/nginx/sites-enabled/velo-client.com
ln -sf /etc/nginx/sites-available/api.velo-client.com /etc/nginx/sites-enabled/api.velo-client.com

mkdir -p /var/www/velo-client.com
if [[ ! -f /var/www/velo-client.com/index.html ]]; then
	echo '<!doctype html><title>Velo Client</title><p>Velo Client - the site is on its way.</p>' > /var/www/velo-client.com/index.html
fi
chown -R "${SUDO_USER:-root}":www-data /var/www/velo-client.com
chmod -R g+rX /var/www/velo-client.com

nginx -t
systemctl reload nginx

say "Getting certificates"
DOMAINS=(-d velo-client.com -d www.velo-client.com -d api.velo-client.com)
[[ $LEGACY == 1 ]] && DOMAINS+=(-d client.asteriasmp.net)
if [[ -n "$EMAIL" ]]; then
	certbot --nginx --non-interactive --agree-tos -m "$EMAIL" --redirect "${DOMAINS[@]}"
else
	certbot --nginx --redirect "${DOMAINS[@]}"
fi

if systemctl cat "$SERVER_UNIT" >/dev/null 2>&1; then
	say "Setting VELO_PUBLIC_URL on $SERVER_UNIT"
	mkdir -p "/etc/systemd/system/$SERVER_UNIT.service.d"
	cat > "/etc/systemd/system/$SERVER_UNIT.service.d/public-url.conf" <<'EOF'
[Service]
Environment=VELO_PUBLIC_URL=https://api.velo-client.com
EOF
	systemctl daemon-reload
	systemctl restart "$SERVER_UNIT"
else
	say "No $SERVER_UNIT service here yet - set VELO_PUBLIC_URL=https://api.velo-client.com when you install it (server/README.md)."
fi

say "Done. Check:"
say "  curl -I https://api.velo-client.com/v1/health"
say "  curl -I https://velo-client.com"
say "Then deploy the site from your PC: velo-site/deploy.sh <user>@<this server>"
say "Tebex: set the webhook to https://api.velo-client.com/v1/store/webhook/tebex in the Tebex dashboard."
say "ayeT: set the postback to https://api.velo-client.com/v1/rewards/ayet (velo-client.com/ads.txt is passed through to the server)."
