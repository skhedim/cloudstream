# Clicks at screen coordinates (physical pixels).
param([Parameter(Mandatory)][int]$X, [Parameter(Mandatory)][int]$Y, [switch]$Right, [switch]$Double)
. "$PSScriptRoot\native.ps1"
[void][KsNative]::SetCursorPos($X, $Y)
$down, $up = if ($Right) { 0x0008, 0x0010 } else { 0x0002, 0x0004 }
$count = if ($Double) { 2 } else { 1 }
for ($i = 0; $i -lt $count; $i++) {
    [KsNative]::mouse_event($down, 0, 0, 0, [UIntPtr]::Zero)
    [KsNative]::mouse_event($up, 0, 0, 0, [UIntPtr]::Zero)
}
