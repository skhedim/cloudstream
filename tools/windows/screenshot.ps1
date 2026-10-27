# Saves a PNG of the whole desktop, or of the first visible window whose title contains -Title.
param([string]$Out = "$env:TEMP\kloudstream-shot.png", [string]$Title)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
. "$PSScriptRoot\native.ps1"
if ($Title) {
    $w = Get-KsWindows | Where-Object { $_.Title -like "*$Title*" } | Select-Object -First 1
    if (-not $w) { throw "No window titled *$Title*" }
    $bounds = New-Object System.Drawing.Rectangle $w.X, $w.Y, $w.Width, $w.Height
} else {
    $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
}
$bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen($bounds.X, $bounds.Y, 0, 0, $bitmap.Size)
$bitmap.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose(); $bitmap.Dispose()
$Out
