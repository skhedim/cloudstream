; KloudStream installer (Inno Setup 6). Built by tools/windows/package.ps1:
;   ISCC /DAppVersion=1.0.0 /DSourceDir=<jpackage app image> /DOutputDir=<dir> kloudstream.iss
; Per-user install (no administrator rights), upgrades in place, French and English wizard.

#define AppName "KloudStream"
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{8B7E4C55-2A43-4C0E-9C4B-7F0A1D2C3E44}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=KloudStream
AppPublisherURL=https://github.com/skhedim/cloudstream
AppSupportURL=https://github.com/skhedim/cloudstream/issues
DefaultDirName={localappdata}\Programs\KloudStream
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
OutputDir={#OutputDir}
OutputBaseFilename=KloudStream-Setup-{#AppVersion}
SetupIconFile=kloudstream.ico
UninstallDisplayIcon={app}\KloudStream.exe
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Old jars and runtime of a previous version must not stay next to the new ones.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\{#AppName}"; Filename: "{app}\KloudStream.exe"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\KloudStream.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\KloudStream.exe"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
