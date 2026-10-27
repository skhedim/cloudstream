# Brings the first visible window whose title contains -Title to the front.
param([Parameter(Mandatory)][string]$Title, [switch]$Maximize)
. "$PSScriptRoot\native.ps1"
$w = Get-KsWindows | Where-Object { $_.Title -like "*$Title*" } | Select-Object -First 1
if (-not $w) { throw "No window titled *$Title*" }
# Restore only a minimized window; maximize on request (SW_RESTORE would un-maximize it).
if ($Maximize) { [void][KsNative]::ShowWindow($w.Handle, 3) } elseif ([KsNative]::IsIconic($w.Handle)) { [void][KsNative]::ShowWindow($w.Handle, 9) }
# Windows only lets a background process take the foreground right after an input event.
(New-Object -ComObject WScript.Shell).SendKeys('%')
[void][KsNative]::SetForegroundWindow($w.Handle)
$w.Title
