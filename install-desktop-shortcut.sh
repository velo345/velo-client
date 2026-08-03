#!/usr/bin/env bash
# Linux/macOS installer - see install-desktop-shortcut.ps1 for the Windows
# equivalent.
#
# Installs a "Velo Client Launcher" entry in the desktop application menu
# (and a double-clickable icon on the actual Desktop folder, wherever the
# current locale/XDG config points that at - e.g. ~/Schreibtisch on a German
# system, not just literally "~/Desktop") that runs launch-launcher.sh from
# wherever this repo currently lives. Re-run this after moving/cloning the
# repo elsewhere to refresh the paths.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
REPO_DIR="$(pwd)"
LAUNCH_SCRIPT="$REPO_DIR/launch-launcher.sh"
ICON="$REPO_DIR/Logo.png"

DESKTOP_ENTRY="[Desktop Entry]
Type=Application
Name=Velo Client Launcher
Comment=Launch Minecraft with your Velo Client mod profiles
Exec=\"$LAUNCH_SCRIPT\"
Icon=$ICON
Terminal=false
Categories=Game;"

mkdir -p "$HOME/.local/share/applications"
printf '%s\n' "$DESKTOP_ENTRY" > "$HOME/.local/share/applications/velo-client-launcher.desktop"
chmod +x "$HOME/.local/share/applications/velo-client-launcher.desktop"
echo "Installed application-menu entry: $HOME/.local/share/applications/velo-client-launcher.desktop"

DESKTOP_DIR="$(command -v xdg-user-dir >/dev/null 2>&1 && xdg-user-dir DESKTOP || echo "$HOME/Desktop")"
if [ -d "$DESKTOP_DIR" ]; then
	DEST="$DESKTOP_DIR/velo-client-launcher.desktop"
	printf '%s\n' "$DESKTOP_ENTRY" > "$DEST"
	chmod +x "$DEST"
	command -v gio >/dev/null 2>&1 && gio set "$DEST" metadata::trusted true 2>/dev/null || true
	echo "Installed desktop icon: $DEST"
	echo "(first double-click may still ask you to confirm trust/allow launching - that's your file manager's safety prompt, one-time)"
else
	echo "No Desktop folder found (checked $DESKTOP_DIR) - only the application-menu entry was installed."
fi
