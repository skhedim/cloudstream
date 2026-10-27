# Moves the mouse pointer (hover) without clicking.
param([Parameter(Mandatory)][int]$X, [Parameter(Mandatory)][int]$Y)
. "$PSScriptRoot\native.ps1"
[void][KsNative]::SetCursorPos($X, $Y)
