; Velo Client Setup - the installer new players download. Built by launcher/build.gradle (jpackageDist, Windows) from
; jpackage's app image; @TOKENS@ are filled in there. Installs for the current user only (no admin prompt) into
; %LOCALAPPDATA%\Programs; updates later come through the launcher itself (launcher-app hop), this installer is only for the
; first install and as the update fallback for installs it made (UpdateService picks it by the uninstaller next to the
; launcher, and runs it with /VERYSILENT).
;
; Look (owner 2026-11-24: "richtig professional, nicht standard"): one borderless window drawn by
; tools/installer/make_images.py (hero art, logo, buttons, toggle) - the [Code] below hides the standard wizard and places
; a few labels and click areas on that picture. Same three steps as before: Install -> progress -> Launch.

#define AppName "@APP_NAME@"
#define AppVersion "@VERSION@"
#define ExeName "@EXE_NAME@"
#define Art "@IMAGES@"

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
DisableWelcomePage=no
DisableDirPage=yes
DisableReadyPage=yes
DisableFinishedPage=no
PrivilegesRequired=lowest
UsePreviousAppDir=yes
CloseApplications=force
RestartApplications=no
OutputDir=@OUTPUT_DIR@
OutputBaseFilename=@OUTPUT_NAME@
SetupIconFile=@ICON@
UninstallDisplayIcon={app}\{#ExeName}
UninstallDisplayName={#AppName}
; lzma2/ultra64 with several block threads ran the compiler out of memory on ~400 MB of input (CI 2026-11-24)
Compression=lzma2/max
SolidCompression=yes
LZMAUseSeparateProcess=yes
WizardStyle=@STYLE@
ShowLanguageDialog=no
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
SetupWindowTitle=Velo Client Setup
ExitSetupTitle=Cancel installation
ExitSetupMessage=Velo Client isn't installed yet. Cancel anyway?

[Files]
Source: "{#Art}\setup-*.bmp"; Flags: dontcopy
Source: "@SOURCE_DIR@\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#ExeName}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#ExeName}"; Check: WantDesktopIcon

[Code]
const
  WIN_W = 820;
  WIN_H = 500;
  PANEL_X = 490;
  BTN_Y = 412;
  BTN_W = 310;
  BTN_H = 48;
  WM_NCLBUTTONDOWN = $A1;
  HTCAPTION = 2;
  WM_SYSCOMMAND = $0112;
  SC_MINIMIZE = $F020;
  CLR_TEXT = $EEEFF4;    // BGR
  CLR_TEXT2 = $A09EA8;
  CLR_TEXT3 = $6E686A;
  CLR_ACCENT = $433CE4;
  CLR_BG = $0A0A0C;
  CLR_TRACK = $2A2324;

var
  Scale: Integer;
  Bg, Btn, Toggle: TBitmapImage;
  DragArea, BtnHit, Heading, Body, PathCaption, PathText, ChangeLink, ShortcutText, Status, Footer, CloseHit, MinHit: TLabel;
  Hovered: Boolean;
  Track, Fill: TPanel;
  DesktopIcon: Boolean;
  BtnState: String;

function ReleaseCapture(): Longint; external 'ReleaseCapture@user32.dll stdcall';
function SendMessageW(hWnd: HWND; Msg: Cardinal; wParam: Longint; lParam: Longint): Longint; external 'SendMessageW@user32.dll stdcall';

function WantDesktopIcon(): Boolean;
begin
  Result := DesktopIcon;
end;

function ArtFile(Name: String): String;
begin
  Result := Name + '-' + IntToStr(Scale) + 'x.bmp';
  ExtractTemporaryFile(Result);
  Result := ExpandConstant('{tmp}\') + Result;
end;

procedure LoadArt(Image: TBitmapImage; Name: String);
begin
  Image.Bitmap.LoadFromFile(ArtFile(Name));
end;

function MakeLabel(X, Y, W, H: Integer; Text: String; Size: Integer; Color: Integer; Bold: Boolean): TLabel;
begin
  Result := TLabel.Create(WizardForm);
  Result.Parent := WizardForm;
  Result.AutoSize := False;
  Result.Transparent := True;
  Result.WordWrap := True;
  // the dark wizard style would repaint every label in its own text colour
  Result.StyleElements := [seClient, seBorder];
  Result.SetBounds(ScaleX(X), ScaleY(Y), ScaleX(W), ScaleY(H));
  Result.Font.Name := 'Segoe UI';
  Result.Font.Size := Size;
  Result.Font.Color := Color;
  if Bold then
    Result.Font.Style := [fsBold];
  Result.Caption := Text;
end;

procedure DragWindow(Sender: TObject; Button: TMouseButton; Shift: TShiftState; X, Y: Integer);
begin
  ReleaseCapture();
  SendMessageW(WizardForm.Handle, WM_NCLBUTTONDOWN, HTCAPTION, 0);
end;

procedure ShowPath();
begin
  PathText.Caption := MinimizePathName(WizardForm.DirEdit.Text, PathText.Font, PathText.Width);
end;

procedure ChangeClick(Sender: TObject);
var
  Dir: String;
begin
  Dir := WizardForm.DirEdit.Text;
  if BrowseForFolder('Where should Velo Client be installed?', Dir, True) then
  begin
    WizardForm.DirEdit.Text := Dir;
    ShowPath();
  end;
end;

procedure ToggleClick(Sender: TObject);
begin
  DesktopIcon := not DesktopIcon;
  if DesktopIcon then
    LoadArt(Toggle, 'setup-toggle-on')
  else
    LoadArt(Toggle, 'setup-toggle-off');
end;

procedure SetButton(State: String);
begin
  BtnState := State;
  Hovered := False;
  LoadArt(Btn, 'setup-' + State);
end;

// hover: the button's click area reports the mouse over it, everything else (the drag area) the mouse elsewhere
procedure ButtonMove(Sender: TObject; Shift: TShiftState; X, Y: Integer);
begin
  if not Hovered and ((BtnState = 'install') or (BtnState = 'launch')) then
  begin
    Hovered := True;
    LoadArt(Btn, 'setup-' + BtnState + '-hover');
  end;
end;

procedure AwayMove(Sender: TObject; Shift: TShiftState; X, Y: Integer);
begin
  if Hovered then
  begin
    Hovered := False;
    LoadArt(Btn, 'setup-' + BtnState);
  end;
end;

procedure ButtonClick(Sender: TObject);
var
  Code: Integer;
begin
  if BtnState = 'install' then
    WizardForm.NextButton.OnClick(WizardForm.NextButton)
  else if BtnState = 'launch' then
  begin
    ExecAsOriginalUser(ExpandConstant('{app}\{#ExeName}'), '', '', SW_SHOWNORMAL, ewNoWait, Code);
    WizardForm.NextButton.OnClick(WizardForm.NextButton);
  end;
end;

procedure CloseClick(Sender: TObject);
begin
  if WizardForm.CurPageID = wpFinished then
    WizardForm.NextButton.OnClick(WizardForm.NextButton)
  else
    WizardForm.CancelButton.OnClick(WizardForm.CancelButton);
end;

procedure MinClick(Sender: TObject);
begin
  SendMessageW(WizardForm.Handle, WM_SYSCOMMAND, SC_MINIMIZE, 0);
end;

procedure InitializeWizard();
begin
  if ScaleX(100) > 125 then
    Scale := 2
  else
    Scale := 1;
  DesktopIcon := True;

  WizardForm.BorderStyle := bsNone;
  WizardForm.Color := CLR_BG;
  WizardForm.ClientWidth := ScaleX(WIN_W);
  WizardForm.ClientHeight := ScaleY(WIN_H);
  WizardForm.Position := poScreenCenter;
  // the standard wizard stays (it drives the install) but out of sight
  WizardForm.OuterNotebook.Visible := False;
  WizardForm.Bevel.Visible := False;
  WizardForm.NextButton.Left := -ScaleX(1000);
  WizardForm.BackButton.Left := -ScaleX(1000);
  WizardForm.CancelButton.Left := -ScaleX(1000);

  Bg := TBitmapImage.Create(WizardForm);
  Bg.Parent := WizardForm;
  Bg.SetBounds(0, 0, ScaleX(WIN_W), ScaleY(WIN_H));
  Bg.Stretch := True;
  LoadArt(Bg, 'setup-bg');
  // the whole window drags (borderless), and moving over it ends the button's hover
  DragArea := MakeLabel(0, 0, WIN_W, WIN_H, '', 10, CLR_TEXT, False);
  DragArea.OnMouseDown := @DragWindow;
  DragArea.OnMouseMove := @AwayMove;

  Heading := MakeLabel(PANEL_X, 150, 300, 30, 'Ready to install', 15, CLR_TEXT, True);
  Body := MakeLabel(PANEL_X, 186, 300, 70,
    'More FPS, a cleaner look, cloaks, emotes and everything you need for PvP and survival. No admin rights needed.',
    10, CLR_TEXT2, False);

  PathCaption := MakeLabel(PANEL_X, 278, 200, 18, 'INSTALL LOCATION', 8, CLR_TEXT3, True);
  PathText := MakeLabel(PANEL_X, 298, 236, 20, '', 10, CLR_TEXT, False);
  PathText.WordWrap := False;
  ChangeLink := MakeLabel(PANEL_X + 246, 298, 64, 20, 'Change', 10, CLR_ACCENT, True);
  ChangeLink.Alignment := taRightJustify;
  ChangeLink.Cursor := crHand;
  ChangeLink.OnClick := @ChangeClick;

  Toggle := TBitmapImage.Create(WizardForm);
  Toggle.Parent := WizardForm;
  Toggle.SetBounds(ScaleX(PANEL_X), ScaleY(340), ScaleX(36), ScaleY(20));
  Toggle.Stretch := True;
  LoadArt(Toggle, 'setup-toggle-on');
  ShortcutText := MakeLabel(PANEL_X, 340, 300, 20, '            Create a desktop shortcut', 10, CLR_TEXT, False);
  ShortcutText.Cursor := crHand;
  ShortcutText.OnClick := @ToggleClick;

  Track := TPanel.Create(WizardForm);
  Track.Parent := WizardForm;
  Track.BevelOuter := bvNone;
  Track.ParentBackground := False;
  Track.StyleElements := [];
  Track.Color := CLR_TRACK;
  Track.SetBounds(ScaleX(PANEL_X), ScaleY(262), ScaleX(BTN_W), ScaleY(6));
  Track.Visible := False;
  Fill := TPanel.Create(WizardForm);
  Fill.Parent := WizardForm;
  Fill.BevelOuter := bvNone;
  Fill.ParentBackground := False;
  Fill.StyleElements := [];
  Fill.Color := CLR_ACCENT;
  Fill.SetBounds(ScaleX(PANEL_X), ScaleY(262), 0, ScaleY(6));
  Fill.Visible := False;
  Status := MakeLabel(PANEL_X, 276, BTN_W, 20, '', 9, CLR_TEXT2, False);

  Btn := TBitmapImage.Create(WizardForm);
  Btn.Parent := WizardForm;
  Btn.SetBounds(ScaleX(PANEL_X), ScaleY(BTN_Y), ScaleX(BTN_W), ScaleY(BTN_H));
  Btn.Stretch := True;
  SetButton('install');
  BtnHit := MakeLabel(PANEL_X, BTN_Y, BTN_W, BTN_H - 4, '', 10, CLR_TEXT, False);
  BtnHit.Cursor := crHand;
  BtnHit.OnClick := @ButtonClick;
  BtnHit.OnMouseMove := @ButtonMove;

  Footer := MakeLabel(PANEL_X, 470, 300, 18, 'Version {#AppVersion}   |   velo-client.com', 8, CLR_TEXT3, False);

  CloseHit := MakeLabel(WIN_W - 36, 8, 28, 28, '', 10, CLR_TEXT, False);
  CloseHit.Cursor := crHand;
  CloseHit.OnClick := @CloseClick;
  MinHit := MakeLabel(WIN_W - 70, 8, 28, 28, '', 10, CLR_TEXT, False);
  MinHit.Cursor := crHand;
  MinHit.OnClick := @MinClick;
end;

procedure ShowChoices(Visible: Boolean);
begin
  PathCaption.Visible := Visible;
  PathText.Visible := Visible;
  ChangeLink.Visible := Visible;
  Toggle.Visible := Visible;
  ShortcutText.Visible := Visible;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpWelcome then
  begin
    ShowPath();
    ShowChoices(True);
  end
  else if CurPageID = wpInstalling then
  begin
    ShowChoices(False);
    Heading.Caption := 'Installing Velo Client';
    Body.Caption := 'This only takes a moment.';
    Track.Visible := True;
    Fill.Visible := True;
    Status.Caption := 'Preparing...';
    SetButton('busy');
    BtnHit.Cursor := crDefault;
  end
  else if CurPageID = wpFinished then
  begin
    ShowChoices(False);
    Track.Visible := False;
    Fill.Visible := False;
    Status.Caption := '';
    Heading.Caption := 'You''re all set';
    Body.Caption := 'Velo Client is installed. Sign in with your Microsoft account and press Play.';
    SetButton('launch');
    BtnHit.Cursor := crHand;
  end;
end;

procedure CurInstallProgressChanged(CurProgress, MaxProgress: Integer);
begin
  if MaxProgress > 0 then
  begin
    Fill.Width := MulDiv(Track.Width, CurProgress, MaxProgress);
    Status.Caption := IntToStr(MulDiv(100, CurProgress, MaxProgress)) + '%';
  end;
end;
