# Starts the staged desktop app (tools/desktop/stage-windows.sh) with the Windows JDK, in the background.
param(
    [string]$App = 'C:\dev\kloudstream-app',
    [string]$Java = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName + '\bin\java.exe'
)
$arguments = @('--enable-native-access=ALL-UNNAMED', '-Dcom.sun.security.enableAIAcaIssuers=true', '-Dfile.encoding=UTF-8',
    '-cp', "`"$App\lib\*`"", 'com.kloudstream.desktop.MainKt')
# No output redirection: it would make java inherit (and hold open) the caller's pipes.
# The app writes its own log in %LOCALAPPDATA%\KloudStream\logs.
$process = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $App -PassThru
"started pid $($process.Id)"
