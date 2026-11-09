#!/usr/bin/env bash
# Kill switch for the Velo Library - takes effect within 3 seconds, no restart, velo-server untouched.
#   tools/server/library-switch.sh off            whole Library API answers 503
#   tools/server/library-switch.sh on
#   tools/server/library-switch.sh off heavy      only spark / plugin checks (or render, ping, write, request)
#   tools/server/library-switch.sh stop|start     stop / start the whole service (nginx answers the 503 then)
set -euo pipefail
TARGET="${TARGET:-velo-brand}"
action="${1:?off|on|stop|start}"
cat_="${2:-}"
file="/opt/velo/library/data/disabled${cat_:+-$cat_}"
case "$action" in
	off) ssh "$TARGET" "sudo -u velo-library touch '$file' 2>/dev/null || touch '$file'; echo 'off: $file'" ;;
	on) ssh "$TARGET" "rm -f '$file'; echo 'on: $file removed'" ;;
	stop) ssh "$TARGET" "systemctl stop velo-library; echo stopped" ;;
	start) ssh "$TARGET" "systemctl start velo-library; echo started" ;;
	*) echo "off|on|stop|start"; exit 2 ;;
esac
