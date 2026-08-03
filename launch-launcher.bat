@echo off
rem Starts the Velo Client Launcher (the JavaFX desktop app under launcher/,
rem not the Minecraft mod itself). This is what the Windows desktop shortcut
rem created by install-desktop-shortcut.ps1 points at, but you can also just
rem double-click this file directly.
cd /d "%~dp0"
echo Starting Velo Client Launcher...
echo (first run can take a few minutes - Gradle has to download itself, Java FX, and Minecraft's libraries)
echo.
call gradlew.bat :launcher:run --console=plain
if errorlevel 1 (
	echo.
	echo Velo Client Launcher failed to start - see the error above.
	echo If this is your first time, make sure Java 21+ ^(25+ for Minecraft 26.1/26.2^) is installed and on PATH.
	pause
)
