# Installs (or upgrades) KloudStream silently for the current user from the built installer, then starts it.
param([string]$Installer = (Get-ChildItem 'C:\dev\kloudstream-build\out' -Filter 'KloudStream-Setup-*.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName,
      [switch]$NoStart)
$ErrorActionPreference = 'Stop'
$setup = Start-Process -FilePath $Installer -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/TASKS=desktopicon' -Wait -PassThru
if ($setup.ExitCode -ne 0) { throw "Installer exit code $($setup.ExitCode)" }
$exe = "$env:LOCALAPPDATA\Programs\KloudStream\KloudStream.exe"
"installed $exe"
if (-not $NoStart) { Start-Process -FilePath $exe; "started" }
