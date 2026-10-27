# Windows half of the desktop packaging (tools/desktop/package-windows.sh prepares $Build\input):
# bundles mpv (pinned build, checked against its published SHA-256), makes the jpackage app image
# with a trimmed Java runtime, then the Inno Setup installer.
param(
    [string]$Build = 'C:\dev\kloudstream-build',
    [string]$Version = '1.0.0',
    # A JDK 17 with jpackage: JAVA_HOME (CI), else the newest Temurin 17 installed.
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Modules = 'java.base,java.desktop,java.instrument,java.management,java.net.http,java.sql,jdk.dynalink,jdk.unsupported,java.logging,java.naming,java.scripting,java.xml,java.prefs,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.net,jdk.accessibility,java.security.jgss'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# mpv for Windows (shinchiro/mpv-winbuild-cmake): one static mpv.exe.
$MpvUrl = 'https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261021/mpv-x86_64-20261021-git-e471496c56.7z'
$MpvSha256 = '4a355f3e89c83fabc730d33fb4e759dfec747806ed2287b61a90d1681b83e8d3'

$jdk = if ($Jdk -and (Test-Path "$Jdk\bin\jpackage.exe")) { $Jdk } else {
    (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Where-Object Name -like 'jdk-17*' |
        Sort-Object Name -Descending | Select-Object -First 1).FullName
}
$iscc = @("$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe") |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $jdk) { throw 'JDK 17 (Temurin) not found' }
if (-not $iscc) { throw 'Inno Setup 6 not found' }

$cache = Join-Path $Build 'cache'
New-Item -ItemType Directory -Force $cache | Out-Null
$archive = Join-Path $cache (Split-Path $MpvUrl -Leaf)
if (-not (Test-Path $archive)) {
    "Downloading mpv"
    Invoke-WebRequest -Uri $MpvUrl -OutFile "$archive.part"
    Move-Item "$archive.part" $archive
}
$hash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLower()
if ($hash -ne $MpvSha256) { Remove-Item $archive; throw "mpv archive checksum mismatch: $hash" }
$extract = Join-Path $cache 'mpv'
if (-not (Test-Path (Join-Path $extract 'mpv.exe'))) {
    New-Item -ItemType Directory -Force $extract | Out-Null
    # 7-Zip when installed (GitHub runners), else Windows' own tar, which reads 7z on Windows 11
    # (a bare tar.exe could resolve to Git's GNU tar, which does not).
    $sevenZip = @((Get-Command 7z.exe -ErrorAction SilentlyContinue).Source, "$env:ProgramFiles\7-Zip\7z.exe") |
        Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if ($sevenZip) { & $sevenZip x $archive "-o$extract" -y | Out-Null } else { & "$env:SystemRoot\System32\tar.exe" -xf $archive -C $extract }
    if ($LASTEXITCODE -ne 0) { throw 'mpv extraction failed' }
}
$mpvTarget = Join-Path $Build 'input\mpv'
New-Item -ItemType Directory -Force $mpvTarget | Out-Null
Copy-Item (Join-Path $extract 'mpv.exe'), (Join-Path $extract 'd3dcompiler_43.dll') $mpvTarget -Force

# uosc (tomasklaen/uosc, LGPL-2.1) replaces mpv's own controls, with KloudStream's settings
# ($Build\mpv-config, from kloudstream-desktop/packaging/mpv), in mpv's portable_config next to mpv.exe.
$UoscUrl = 'https://github.com/tomasklaen/uosc/releases/download/5.13.0/uosc.zip'
$UoscSha256 = '4be9da3289285300fa374496c3f1bfd7bb20ac08e890d25bd5a06b28eebe4882'
$uoscArchive = Join-Path $cache 'uosc-5.13.0.zip'
if (-not (Test-Path $uoscArchive)) {
    "Downloading uosc"
    Invoke-WebRequest -Uri $UoscUrl -OutFile "$uoscArchive.part"
    Move-Item "$uoscArchive.part" $uoscArchive
}
$hash = (Get-FileHash $uoscArchive -Algorithm SHA256).Hash.ToLower()
if ($hash -ne $UoscSha256) { Remove-Item $uoscArchive; throw "uosc archive checksum mismatch: $hash" }
$portable = Join-Path $mpvTarget 'portable_config'
if (Test-Path $portable) { Remove-Item $portable -Recurse -Force }
New-Item -ItemType Directory -Force $portable | Out-Null
Expand-Archive $uoscArchive -DestinationPath $portable -Force
Copy-Item (Join-Path $Build 'mpv-config\*') $portable -Recurse -Force

"Creating the app image"
$image = Join-Path $Build 'image'
if (Test-Path $image) { Remove-Item $image -Recurse -Force }
& "$jdk\bin\jpackage.exe" --type app-image --name KloudStream --app-version $Version --vendor KloudStream `
    --description 'KloudStream' --input (Join-Path $Build 'input') --main-jar kloudstream-desktop-jvm.jar `
    --main-class com.kloudstream.desktop.MainKt --icon (Join-Path $Build 'kloudstream.ico') `
    --add-modules $Modules --jlink-options '--strip-debug --no-man-pages --no-header-files --compress=2' `
    --java-options '--enable-native-access=ALL-UNNAMED' --java-options '-Dcom.sun.security.enableAIAcaIssuers=true' `
    --java-options '-Dfile.encoding=UTF-8' --java-options "-Dkloudstream.version=$Version" --java-options '-Xmx2g' `
    --dest $image
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

"Building the installer"
$output = Join-Path $Build 'out'
& $iscc /Q "/DAppVersion=$Version" "/DSourceDir=$image\KloudStream" "/DOutputDir=$output" (Join-Path $Build 'kloudstream.iss')
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup failed' }
Get-ChildItem $output -Filter "KloudStream-Setup-$Version.exe" | ForEach-Object { "$($_.FullName) $([math]::Round($_.Length / 1MB)) MB" }
