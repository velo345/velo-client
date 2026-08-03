# Windows installer - see install-desktop-shortcut.sh for the Linux/macOS
# equivalent.
#
# Installs a "Velo Client Launcher" shortcut on the Windows Desktop that runs
# launch-launcher.bat from wherever this repo currently lives. Re-run this
# after moving/cloning the repo elsewhere to refresh the shortcut's target.
#
# [Environment]::GetFolderPath("Desktop") is used rather than a hardcoded
# "$HOME\Desktop" path so this lands in the right place even when the Desktop
# folder is renamed/localized or redirected (e.g. by OneDrive or a non-English
# Windows install) - the same reason the Linux installer uses xdg-user-dir
# instead of assuming "~/Desktop".
#
# Run this from PowerShell: .\install-desktop-shortcut.ps1
$ErrorActionPreference = "Stop"

$repoDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$launchScript = Join-Path $repoDir "launch-launcher.bat"
$icon = Join-Path $repoDir "Logo.ico"
$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop "Velo Client Launcher.lnk"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $launchScript
$shortcut.WorkingDirectory = $repoDir
$shortcut.Description = "Launch Minecraft with your Velo Client mod profiles"
if (Test-Path $icon) {
	$shortcut.IconLocation = $icon
}
$shortcut.Save()

Write-Host "Installed desktop shortcut: $shortcutPath"
