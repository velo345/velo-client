#!/usr/bin/env bash
# Moves the Velo server (and, if you run it, the Discord bot) to another machine - data, settings, services, proxy.
#
#   migrate.sh pack    [archive]          run ON the old server: bundle everything into one archive
#   migrate.sh restore <archive>          run ON the new server: unpack it, install the services, start them
#   migrate.sh pull    <user@old-host>    run on the NEW server: pack on the old one over ssh, copy it here, restore
#   migrate.sh push    <user@new-host>    run on the OLD server: pack, copy to the new one over ssh, restore there
#   migrate.sh check   [url]              is the (new) server answering? (default http://localhost:8787)
#
# What travels: the server's data directory (store.db - coins, Velo+, ownership - social.json, capes, cosmetics, news,
# reports, Discord links, addon cache...), the server's and the bot's settings/secrets (the Environment= lines of their
# systemd units, or an env file), the bot's data directory (bot-config.json), both jars, the systemd units and the Caddy
# config. The archive holds your secrets (admin token, Tebex keys, Discord token): it is created with mode 600 - copy it
# over ssh only, and delete it afterwards.
#
# Defaults match server/README.md (/opt/velo-server, service velo-server; bot: /opt/velo-bot, service velo-bot).
# Override with SERVER_DIR, BOT_DIR, SERVER_UNIT, BOT_UNIT, SERVER_USER (and --yes to skip questions).
#
# Typical move (new machine = Ubuntu/Debian with ssh access to the old one):
#   1. on the new server:   git clone <repo> && cd <repo> && tools/server/migrate.sh pull root@old-server
#   2. it says what it did; switch DNS (or the proxy) to the new machine;
#   3. tools/server/migrate.sh check https://your.domain   - then stop the old services.
# Nothing is deleted on the old server.
set -euo pipefail

SERVER_DIR="${SERVER_DIR:-/opt/velo-server}"
BOT_DIR="${BOT_DIR:-/opt/velo-bot}"
SERVER_UNIT="${SERVER_UNIT:-velo-server}"
BOT_UNIT="${BOT_UNIT:-velo-bot}"
SERVER_USER="${SERVER_USER:-velo-server}"
YES=0
for arg in "$@"; do [[ "$arg" == "--yes" ]] && YES=1; done
ARGS=()
for arg in "$@"; do [[ "$arg" == "--yes" ]] || ARGS+=("$arg"); done
set -- "${ARGS[@]:-}"

say() { printf '\033[1;31m[velo-migrate]\033[0m %s\n' "$*"; }
die() { say "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is needed but not installed"; }
ask() { [[ "$YES" == 1 ]] && return 0; read -r -p "$1 [y/N] " reply; [[ "$reply" == [yY]* ]]; }
sudo_() { if [[ $EUID -eq 0 ]]; then "$@"; else sudo "$@"; fi; }

unit_file() { systemctl cat "$1" 2>/dev/null | sed -n 's/^# \(\/.*\.service\)$/\1/p' | head -1 || true; }
unit_env() { # Environment= lines of a unit, one per line (without the key prefix handling)
	systemctl show "$1" -p Environment --value 2>/dev/null | tr ' ' '\n' | grep '=' || true
}
unit_workdir() { systemctl show "$1" -p WorkingDirectory --value 2>/dev/null; }

cmd_pack() {
	need tar
	local out="${1:-velo-migration-$(date +%Y%m%d-%H%M).tar.gz}"
	work="$(mktemp -d)"; trap 'rm -rf "${work:-}"' EXIT
	local pack="$work/velo-migration"
	mkdir -p "$pack/server" "$pack/bot" "$pack/system"
	say "Packing the server from $SERVER_DIR"
	[[ -d "$SERVER_DIR" ]] || die "$SERVER_DIR not found - set SERVER_DIR"
	local data="${VELO_DATA_DIR:-}"
	[[ -z "$data" ]] && data="$(unit_env "$SERVER_UNIT" | sed -n 's/^VELO_DATA_DIR=//p' | head -1)"
	[[ -z "$data" ]] && data="$SERVER_DIR/data"
	[[ -d "$data" ]] || die "Data directory $data not found (VELO_DATA_DIR)"
	echo "$data" > "$pack/server/data-path"
	say "  data: $data"
	# store.db is written to constantly: take a consistent snapshot with sqlite when it's there.
	mkdir -p "$pack/server/data"
	if command -v sqlite3 >/dev/null 2>&1 && [[ -f "$data/store.db" ]]; then
		( cd "$data" && tar --exclude=store.db --exclude=store.db-wal --exclude=store.db-shm -cf - . ) | ( cd "$pack/server/data" && tar -xf - )
		sqlite3 "$data/store.db" ".backup '$pack/server/data/store.db'"
		say "  store.db: consistent snapshot taken"
	else
		cp -a "$data/." "$pack/server/data/"
		if [[ -f "$data/store.db" ]]; then say "  (no sqlite3 here: store.db copied as-is - stop the server first for a perfectly consistent copy)"; fi
	fi
	if [[ -f "$SERVER_DIR/velo-server.jar" ]]; then cp "$SERVER_DIR/velo-server.jar" "$pack/server/"; fi
	unit_env "$SERVER_UNIT" > "$pack/server/environment" || true
	local unit; unit="$(unit_file "$SERVER_UNIT")"
	if [[ -n "$unit" && -f "$unit" ]]; then cp "$unit" "$pack/system/$SERVER_UNIT.service"; fi
	if systemctl list-unit-files 2>/dev/null | grep -q "^$BOT_UNIT.service" || [[ -d "$BOT_DIR" ]]; then
		say "Packing the Discord bot from $BOT_DIR"
		if [[ -f "$BOT_DIR/velo-bot.jar" ]]; then cp "$BOT_DIR/velo-bot.jar" "$pack/bot/"; fi
		local botdata; botdata="$(unit_env "$BOT_UNIT" | sed -n 's/^BOT_DATA_DIR=//p' | head -1)"
		[[ -z "$botdata" ]] && botdata="$BOT_DIR/bot-data"
		if [[ -d "$botdata" ]]; then cp -a "$botdata" "$pack/bot/bot-data"; echo "$botdata" > "$pack/bot/data-path"; fi
		unit_env "$BOT_UNIT" > "$pack/bot/environment" || true
		local bunit; bunit="$(unit_file "$BOT_UNIT")"
		if [[ -n "$bunit" && -f "$bunit" ]]; then cp "$bunit" "$pack/system/$BOT_UNIT.service"; fi
	fi
	if [[ -f /etc/caddy/Caddyfile ]]; then cp /etc/caddy/Caddyfile "$pack/system/Caddyfile"; say "Caddy config included"; fi
	{ echo "created=$(date -Is)"; echo "host=$(hostname)"; echo "server_dir=$SERVER_DIR"; echo "bot_dir=$BOT_DIR"; } > "$pack/manifest"
	( umask 077; tar -C "$work" -czf "$out" velo-migration )
	chmod 600 "$out"
	say "Archive: $out ($(du -h "$out" | cut -f1)) - it contains your secrets; copy it over ssh only and delete it afterwards."
	echo "$out"
}

cmd_restore() {
	local archive="${1:-}"; [[ -f "$archive" ]] || die "Usage: migrate.sh restore <archive>"
	need tar; need systemctl
	command -v java >/dev/null 2>&1 || say "Java isn't installed here - install a JDK 21+ (e.g. apt install openjdk-21-jre-headless) before starting the services"
	work="$(mktemp -d)"; trap 'rm -rf "${work:-}"' EXIT
	tar -C "$work" -xzf "$archive"
	local pack="$work/velo-migration"; [[ -d "$pack" ]] || die "Not a Velo migration archive"
	say "Archive from $(sed -n 's/^host=//p' "$pack/manifest") ($(sed -n 's/^created=//p' "$pack/manifest"))"
	if systemctl is-active --quiet "$SERVER_UNIT" 2>/dev/null; then
		ask "A $SERVER_UNIT service is already running here. Stop it and overwrite its data?" || die "Aborted"
		sudo_ systemctl stop "$SERVER_UNIT"
	fi
	say "Installing the server into $SERVER_DIR"
	sudo_ mkdir -p "$SERVER_DIR/data"
	id "$SERVER_USER" >/dev/null 2>&1 || sudo_ useradd --system --no-create-home "$SERVER_USER"
	if [[ -f "$pack/server/velo-server.jar" ]]; then sudo_ cp "$pack/server/velo-server.jar" "$SERVER_DIR/velo-server.jar"; fi
	sudo_ cp -a "$pack/server/data/." "$SERVER_DIR/data/"
	sudo_ chown -R "$SERVER_USER:$SERVER_USER" "$SERVER_DIR/data"
	# Unit: keep the old one, but point every path at this machine's layout.
	if [[ -f "$pack/system/$SERVER_UNIT.service" ]]; then
		local old_dir; old_dir="$(sed -n 's/^server_dir=//p' "$pack/manifest")"
		local old_data; old_data="$(cat "$pack/server/data-path" 2>/dev/null || true)"
		sed -e "s#${old_data:-@@none@@}#$SERVER_DIR/data#g" -e "s#${old_dir:-@@none@@}#$SERVER_DIR#g" "$pack/system/$SERVER_UNIT.service" \
			| sudo_ tee "/etc/systemd/system/$SERVER_UNIT.service" >/dev/null
	else
		say "No server unit in the archive - writing one from the saved environment"
		{
			echo "[Unit]"; echo "Description=Velo Client server"; echo "After=network.target"; echo; echo "[Service]"
			echo "ExecStart=/usr/bin/java -jar $SERVER_DIR/velo-server.jar"; echo "WorkingDirectory=$SERVER_DIR"; echo "Restart=on-failure"
			echo "Environment=VELO_DATA_DIR=$SERVER_DIR/data"
			grep -v '^VELO_DATA_DIR=' "$pack/server/environment" | sed 's/^/Environment=/'
			echo "User=$SERVER_USER"; echo "NoNewPrivileges=true"; echo; echo "[Install]"; echo "WantedBy=multi-user.target"
		} | sudo_ tee "/etc/systemd/system/$SERVER_UNIT.service" >/dev/null
	fi
	sudo_ chmod 640 "/etc/systemd/system/$SERVER_UNIT.service"
	if [[ -f "$pack/bot/velo-bot.jar" || -d "$pack/bot/bot-data" ]]; then
		say "Installing the Discord bot into $BOT_DIR"
		sudo_ mkdir -p "$BOT_DIR"
		if [[ -f "$pack/bot/velo-bot.jar" ]]; then sudo_ cp "$pack/bot/velo-bot.jar" "$BOT_DIR/velo-bot.jar"; fi
		if [[ -d "$pack/bot/bot-data" ]]; then sudo_ cp -a "$pack/bot/bot-data" "$BOT_DIR/"; fi
		sudo_ chown -R "$SERVER_USER:$SERVER_USER" "$BOT_DIR"
		if [[ -f "$pack/system/$BOT_UNIT.service" ]]; then
			local old_bot; old_bot="$(sed -n 's/^bot_dir=//p' "$pack/manifest")"
			local old_botdata; old_botdata="$(cat "$pack/bot/data-path" 2>/dev/null || true)"
			sed -e "s#${old_botdata:-@@none@@}#$BOT_DIR/bot-data#g" -e "s#${old_bot:-@@none@@}#$BOT_DIR#g" "$pack/system/$BOT_UNIT.service" \
				| sudo_ tee "/etc/systemd/system/$BOT_UNIT.service" >/dev/null
			sudo_ chmod 640 "/etc/systemd/system/$BOT_UNIT.service"
		else
			say "The bot had no unit file: create $BOT_UNIT.service yourself (secrets are in $pack/bot/environment of the archive)"
		fi
	fi
	if [[ -f "$pack/system/Caddyfile" && -d /etc/caddy ]]; then
		if ask "Install the Caddy config from the old server (replaces /etc/caddy/Caddyfile)?"; then
			[[ -f /etc/caddy/Caddyfile ]] && sudo_ cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak-$(date +%s)"
			sudo_ cp "$pack/system/Caddyfile" /etc/caddy/Caddyfile
			sudo_ systemctl reload caddy 2>/dev/null || true
		fi
	elif [[ -f "$pack/system/Caddyfile" ]]; then
		say "The old server used Caddy (config saved in the archive) - install Caddy here (https://caddyserver.com/docs/install) and copy it to /etc/caddy/Caddyfile"
	fi
	sudo_ systemctl daemon-reload
	sudo_ systemctl enable --now "$SERVER_UNIT"
	if [[ -f "/etc/systemd/system/$BOT_UNIT.service" ]]; then sudo_ systemctl enable --now "$BOT_UNIT"; fi
	sleep 3
	cmd_check "http://localhost:$(unit_env "$SERVER_UNIT" | sed -n 's/^VELO_SERVER_PORT=//p' | head -1 | grep . || echo 8787)" || true
	cat <<DONE

Done. What's left:
  1. Point your domain at this machine (DNS A/AAAA record) - the launcher and the game use the same address, nothing to update in them.
  2. Check:   tools/server/migrate.sh check https://your.domain
  3. Stop the old server and bot (sudo systemctl disable --now $SERVER_UNIT $BOT_UNIT) so nothing runs twice -
     especially the bot (two copies answer every message).
  4. Tebex: if you pinned a webhook IP/URL, nothing changes while the domain stays. Delete the archive: shred -u $archive
DONE
}

cmd_check() {
	local base="${1:-http://localhost:8787}"
	need curl
	if curl -fsS -m 10 "$base/v1/health"; then echo; say "Server answers at $base"; else say "No answer from $base (journalctl -u $SERVER_UNIT -n 50)"; return 1; fi
}

cmd_pull() {
	local host="${1:-}"; [[ -n "$host" ]] || die "Usage: migrate.sh pull user@old-host"
	need ssh; need scp
	say "Packing on $host (run with sudo there if the data belongs to another user)"
	local remote_archive="/tmp/velo-migration-$$.tar.gz"
	ssh "$host" "SERVER_DIR='$SERVER_DIR' BOT_DIR='$BOT_DIR' SERVER_UNIT='$SERVER_UNIT' BOT_UNIT='$BOT_UNIT' bash -s -- pack '$remote_archive'" < "$0" >/dev/null
	local local_archive; local_archive="$(mktemp -u /tmp/velo-migration-XXXXXX.tar.gz)"
	( umask 077; scp -q "$host:$remote_archive" "$local_archive" )
	ssh "$host" "shred -u '$remote_archive' 2>/dev/null || rm -f '$remote_archive'"
	cmd_restore "$local_archive"
	shred -u "$local_archive" 2>/dev/null || rm -f "$local_archive"
}

cmd_push() {
	local host="${1:-}"; [[ -n "$host" ]] || die "Usage: migrate.sh push user@new-host"
	need ssh; need scp
	local archive; archive="$(mktemp -u /tmp/velo-migration-XXXXXX.tar.gz)"
	cmd_pack "$archive" >/dev/null
	( scp -q "$archive" "$host:/tmp/velo-migration.tar.gz" )
	ssh -t "$host" "bash -s -- restore /tmp/velo-migration.tar.gz --yes" < "$0"
	ssh "$host" "shred -u /tmp/velo-migration.tar.gz 2>/dev/null || rm -f /tmp/velo-migration.tar.gz"
	shred -u "$archive" 2>/dev/null || rm -f "$archive"
}

case "${1:-}" in
	pack) shift; cmd_pack "$@" ;;
	restore) shift; cmd_restore "$@" ;;
	pull) shift; cmd_pull "$@" ;;
	push) shift; cmd_push "$@" ;;
	check) shift; cmd_check "$@" ;;
	*) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
