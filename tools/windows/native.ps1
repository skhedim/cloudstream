# Win32 helpers shared by the scripts: window enumeration, focus, mouse.
Add-Type -ErrorAction SilentlyContinue @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class KsNative {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc proc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int max);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out RECT rect, int size);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
[void][KsNative]::SetProcessDPIAware()

function Get-KsWindows {
    $list = New-Object System.Collections.ArrayList
    $proc = [KsNative+EnumProc]{
        param($h, $l)
        if ([KsNative]::IsWindowVisible($h)) {
            $sb = New-Object System.Text.StringBuilder 512
            [void][KsNative]::GetWindowText($h, $sb, 512)
            if ($sb.Length -gt 0) {
                $r = New-Object KsNative+RECT
                [void][KsNative]::DwmGetWindowAttribute($h, 9, [ref]$r, 16)
                $wpid = 0; [void][KsNative]::GetWindowThreadProcessId($h, [ref]$wpid)
                $name = (Get-Process -Id $wpid -ErrorAction SilentlyContinue).ProcessName
                [void]$list.Add([pscustomobject]@{ Handle = $h; Title = $sb.ToString(); Process = $name;
                    X = $r.Left; Y = $r.Top; Width = $r.Right - $r.Left; Height = $r.Bottom - $r.Top })
            }
        }
        $true
    }
    [void][KsNative]::EnumWindows($proc, [IntPtr]::Zero)
    $list | Where-Object { $_.Width -gt 0 -and $_.Height -gt 0 }
}
