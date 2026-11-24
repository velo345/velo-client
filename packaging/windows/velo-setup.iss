; Velo Client Setup - the installer new players download (owner 2026-11-24: "a better designed installer than the
; standard one"). Built by launcher/build.gradle (jpackageDist, Windows) from jpackage's app image; @TOKENS@ are filled in
; there. Installs for the current user only (no admin prompt) into %LOCALAPPDATA%\Programs; updates later come through
; the launcher itself (launcher-app hop), this installer is only for the first install and as the update fallback for
; installs it made (UpdateService picks it by the uninstaller next to the launcher).

#define AppName "@APP_NAME@"
#define AppVersion "@VERSION@"
#define ExeName "@EXE_NAME@"

[Setup]
AppId={{@APP_ID@}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Velo Client
AppPublisherURL=https://velo-client.com
AppSupportURL=https://velo-client.com/discord
AppUpdatesURL=https://velo-client.com/download
VersionInfoVersion=@NUMERIC_VERSION@
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=auto
DisableReadyPage=yes
PrivilegesRequired=lowest
UsePreviousAppDir=yes
CloseApplications=force
RestartApplications=no
OutputDir=@OUTPUT_DIR@
OutputBaseFilename=@OUTPUT_NAME@
SetupIconFile=@ICON@
UninstallDisplayIcon={app}\{#ExeName}
UninstallDisplayName={#AppName}
Compression=lzma2/ultra64
SolidCompression=yes
LZMANumBlockThreads=4
WizardStyle=@STYLE@
WizardSizePercent=110
WizardImageFile=@IMAGES@\wizard-side-1x.bmp,@IMAGES@\wizard-side-2x.bmp
WizardSmallImageFile=@IMAGES@\wizard-small-1x.bmp,@IMAGES@\wizard-small-2x.bmp
WizardImageStretch=no
ShowLanguageDialog=no
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel1=Welcome to Velo Client
WelcomeLabel2=The Minecraft client built for speed: more FPS, a cleaner look, cloaks, emotes and everything you need for PvP and survival.%n%nThis installs the Velo Client launcher on your PC - no admin rights needed. It only takes a moment.
FinishedHeadingLabel=Velo Client is ready
FinishedLabelNoIcons=Velo Client is installed. Sign in with your Microsoft account and press Play.
FinishedLabel=Velo Client is installed. Sign in with your Microsoft account and press Play.
ClickFinish=Click Finish to close Setup.
SelectTasksDesc=Anything extra?
ButtonInstall=&Install

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "@SOURCE_DIR@\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#ExeName}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#ExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#ExeName}"; Description: "Start Velo Client now"; Flags: nowait postinstall skipifsilent
