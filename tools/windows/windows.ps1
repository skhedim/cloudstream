# Lists visible top-level windows: title, process and bounds (physical pixels).
param([string]$Title)
. "$PSScriptRoot\native.ps1"
Get-KsWindows | Where-Object { -not $Title -or $_.Title -like "*$Title*" } |
    Format-Table -AutoSize Title, Process, X, Y, Width, Height | Out-String -Width 220
