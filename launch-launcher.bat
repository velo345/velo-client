@echo off
rem Starts the Velo Client Launcher (the JavaFX desktop app under launcher/,
rem not the Minecraft mod itself). This is what the Windows desktop shortcut
rem created by install-desktop-shortcut.ps1 points at, but you can also just
rem double-click this file directly.
cd /d "%~dp0"
call gradlew.bat :launcher:run -q --console=plain
