# Opens KloudStream's player (the installed mpv and its uosc) on a local test clip, with -Settings
# (kloudstream-desktop/packaging/mpv) laid over the installed ones, and saves a PNG of each player
# state through mpv's own window screenshots. Nothing is streamed and no extension runs.
param([Parameter(Mandatory)][string]$Video, [Parameter(Mandatory)][string]$Out, [string]$Settings,
      [string]$Mpv = "$env:LOCALAPPDATA\Programs\KloudStream\app\mpv\mpv.exe", [switch]$Windowed)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Mpv)) { throw "mpv not found ($Mpv): install KloudStream or pass -Mpv" }
New-Item -ItemType Directory -Force $Out | Out-Null
Get-ChildItem $Out -Filter '*.png' | Remove-Item
# The app's mpv reads portable_config next to mpv.exe: the preview copies it, then -Settings over it.
$config = Join-Path $Out 'config'
if (Test-Path $config) { Remove-Item $config -Recurse -Force }
Copy-Item (Join-Path (Split-Path $Mpv) 'portable_config') $config -Recurse
if ($Settings) { Copy-Item (Join-Path $Settings '*') $config -Recurse -Force }

$pipeName = "kloudstream-preview-$PID"
# As the app names it (Title - Episode label), in ASCII source: "Titre de la serie - S1 E4 . Le depart".
$title = "Titre de la s$([char]0xE9)rie $([char]0x2014) S1 $([char]0xC9)4 $([char]0xB7) Le d$([char]0xE9)part"
# The app's arguments (player/Mpv.kt), paused 38 minutes into the clip.
$arguments = "--config-dir=$config", "--input-ipc-server=\\.\pipe\$pipeName", '--force-window=immediate',
    "--fullscreen=$(if ($Windowed) { 'no' } else { 'yes' })", '--geometry=1600x900', '--pause', '--start=38:12',
    "--force-media-title=$title", '--alang=fr,fre,fra,french', '--slang=fr,fre,fra,french',
    '--hwdec=auto-safe', '--input-default-bindings=yes', '--input-vo-keyboard=yes', '--osd-duration=2500',
    '--sub-scale=1.0', "--log-file=$(Join-Path $Out 'mpv.log')", $Video
$process = Start-Process -FilePath $Mpv -ArgumentList ($arguments | ForEach-Object { '"' + $_ + '"' }) -PassThru
$pipe = $null
try {
    $pipe = New-Object System.IO.Pipes.NamedPipeClientStream '.', $pipeName, ([System.IO.Pipes.PipeDirection]::InOut)
    $pipe.Connect(15000)
    $reader = New-Object System.IO.StreamReader $pipe
    $writer = New-Object System.IO.StreamWriter $pipe
    $writer.AutoFlush = $true
    $script:request = 0
    function Invoke-Mpv([object[]]$Command) {
        $script:request++
        $writer.WriteLine((@{ command = $Command; request_id = $script:request } | ConvertTo-Json -Compress))
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { throw 'mpv closed its IPC' }
            $reply = ConvertFrom-Json $line
            if ($reply.request_id -eq $script:request) {
                if ($reply.error -ne 'success') { throw "$($Command -join ' '): $($reply.error)" }
                return $reply.data
            }
        }
    }
    function Save-State([string]$Name) {
        Start-Sleep -Milliseconds 800
        [void](Invoke-Mpv @('screenshot-to-file', (Join-Path $Out "$Name.png"), 'window'))
        "saved $Name.png"
    }
    # Moves mpv's pointer (not the system's) to a fraction of the window.
    function Point([double]$X, [double]$Y) { [void](Invoke-Mpv @('mouse', [int]($width * $X), [int]($height * $Y))) }

    $deadline = (Get-Date).AddSeconds(20)
    while ($true) {
        try { if ((Invoke-Mpv @('get_property', 'time-pos')) -ge 2290) { break } } catch { }
        if ((Get-Date) -gt $deadline) { throw 'mpv did not open the clip' }
        Start-Sleep -Milliseconds 250
    }
    $width = Invoke-Mpv @('get_property', 'osd-width')
    $height = Invoke-Mpv @('get_property', 'osd-height')
    "window ${width}x${height}"
    # Positions from scripts/kloudstream.lua's layout, designed at 1080 lines.
    $s = $height / 1080
    function Hover([double]$X, [double]$Y) { [void](Invoke-Mpv @('mouse', [int]$X, [int]$Y)) }
    $row = $height - 62 * $s
    Point 0.5 0.45; Start-Sleep -Seconds 2
    Save-State '1-pause'
    Hover ($width * 0.3) ($height - 138 * $s); Save-State '2-timeline'
    Hover ($width - 266 * $s) $row; Save-State '3-button'
    Hover (352 * $s) $row; Save-State '4-volume'
    Hover (74 * $s) (60 * $s); Save-State '5-back'
    Point 0.5 0.45
    foreach ($menu in 'subtitles', 'audio', 'speed') {
        [void](Invoke-Mpv @('script-binding', "kloudstream/$menu")); Save-State "6-menu-$menu"
        [void](Invoke-Mpv @('keypress', 'ESC'))
    }
    [void](Invoke-Mpv @('set_property', 'pause', $false)); Start-Sleep -Milliseconds 150
    [void](Invoke-Mpv @('screenshot-to-file', (Join-Path $Out '7-flash.png'), 'window')); 'saved 7-flash.png'
    Point 0.5 0.45; Start-Sleep -Seconds 3.2
    Save-State '8-playing'

    # Clicks through mpv's pointer, once the controls have appeared under it.
    function Click([double]$X, [double]$Y) {
        Hover $X $Y; Start-Sleep -Milliseconds 400
        [void](Invoke-Mpv @('mouse', [int]$X, [int]$Y, 0, 'single')); Start-Sleep -Milliseconds 400
    }
    Click ($width / 2) ($height - 138 * $s)
    "check timeline click: position $([int](Invoke-Mpv @('get_property', 'time-pos'))) s (expected 2700)"
    Click ($width - 266 * $s) $row
    "check subtitles button: menu $(Invoke-Mpv @('get_property', 'user-data/uosc/menu/type'))"
    [void](Invoke-Mpv @('keypress', 'ESC')); Start-Sleep -Milliseconds 300
    Click ($width / 2) ($height * 0.45)
    "check picture click: pause $(Invoke-Mpv @('get_property', 'pause'))"
} finally {
    if ($pipe) {
        try { [void](Invoke-Mpv @('quit')) } catch { }
        $pipe.Dispose()
    }
    if (-not $process.WaitForExit(5000)) { Stop-Process -Id $process.Id -Force }
}
