# Sends keys to the foreground window, in System.Windows.Forms.SendKeys syntax ("{DOWN}", "^f", "abc").
param([Parameter(Mandatory)][string]$Keys, [int]$DelayMs = 0)
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.SendKeys]::SendWait($Keys)
if ($DelayMs -gt 0) { Start-Sleep -Milliseconds $DelayMs }
