# Installs (or upgrades) KloudStream silently for the current user from the built installer, then starts it.
param([string]$Installer = (Get-ChildItem 'C:\dev\kloudstream-build\out' -Filter 'KloudStream-Setup-*.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName,
      [switch]$NoStart)
$ErrorActionPreference = 'Stop'
# A running app keeps its files open: close it (its window first, so it saves), then force it.
$running = Get-Process KloudStream -ErrorAction SilentlyContinue
$running | ForEach-Object { [void]$_.CloseMainWindow() }
$running | ForEach-Object { if (-not $_.WaitForExit(5000)) { Stop-Process -Id $_.Id -Force } }
$setup = Start-Process -FilePath $Installer -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/TASKS=desktopicon' -Wait -PassThru
if ($setup.ExitCode -ne 0) { throw "Installer exit code $($setup.ExitCode)" }
$exe = "$env:LOCALAPPDATA\Programs\KloudStream\KloudStream.exe"
"installed $exe"
if (-not $NoStart) { Start-Process -FilePath $exe; "started" }
