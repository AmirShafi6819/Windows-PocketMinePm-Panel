; Inno Setup script: wraps the jpackage app image into a single Setup.exe
; Per-user install (no UAC) - friendly for a personal server panel.
; Paths are relative to this file's folder (installer/).
; Env vars used: PMPANEL_VERSION (e.g. 2.8.0), PMPANEL_APPDIR (path to the jpackage image).

#define AppName "PM Panel"
#define AppVersion GetEnv('PMPANEL_VERSION')
#define PackDir GetEnv('PMPANEL_APPDIR')

[Setup]
AppId={{F7C2A1D4-8E93-4B6A-9C5D-2E1F0A3B4C5D}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion} (Windows)
AppPublisher=EXBYPIXEL
AppPublisherURL=https://github.com/EXBYPIXEL/Windows-PacketMinePm-Panel
AppSupportURL=https://github.com/EXBYPIXEL/Windows-PacketMinePm-Panel/issues
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
UninstallDisplayName={#AppName} (PocketMine server panel for Windows)
UninstallDisplayIcon={app}\PMPanel.exe
SetupIconFile=pmpanel.ico
OutputDir=..\build\installer-out
OutputBaseFilename=PMPanel-Setup-{#AppVersion}-x64
Compression=lzma2/max
SolidCompression=yes
LZMANumBlockThreads=4
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
MinVersion=10.0
WizardStyle=modern
WizardSmallImageFile=pmpanel-wizard.bmp

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce

[Files]
Source: "{#PackDir}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\PMPanel.exe"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\PMPanel.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\PMPanel.exe"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[Messages]
WelcomeLabel2=This will install PM Panel - the PocketMine-MP server panel for Windows.[newline][newline]Everything is bundled (PHP runtime, PocketMine-MP, fake-player engine); no other dependencies are needed. Your server data lives in %APPDATA%\PMPanel.
FinishedLabelNoIcons=PM Panel has been installed.[newline][newline]Your servers are ready - launch PM Panel and press Start.
FinishedLabel=PM Panel has been installed.[newline][newline]Your servers are ready - launch PM Panel and press Start.

[UninstallDelete]
; server data lives in %APPDATA%\PMPanel and is intentionally kept on uninstall
Type: files; Name: "{app}\PMPanel.exe"
