# Turns the mouse wheel at screen coordinates: negative -Notches scroll down.
param([Parameter(Mandatory)][int]$X, [Parameter(Mandatory)][int]$Y, [int]$Notches = -3)
. "$PSScriptRoot\native.ps1"
[void][KsNative]::SetCursorPos($X, $Y)
# The wheel delta is a signed value passed as an unsigned one (two's complement).
$delta = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int32]($Notches * 120)), 0)
[KsNative]::mouse_event(0x0800, 0, 0, $delta, [UIntPtr]::Zero)
