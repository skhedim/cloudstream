# Stops the desktop app started by run-app.ps1 (java processes running com.kloudstream.desktop.MainKt).
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like '*com.kloudstream.desktop.MainKt*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force; "stopped $($_.ProcessId)" }
