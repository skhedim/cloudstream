# Windows tooling (desktop port)

Drive the Windows side of a WSL2 machine from WSL: build, run and inspect the desktop app.

- `sync.sh` copies the repository to `C:\dev\kloudstream` (Windows builds are slow over `\\wsl$`).
- `win.sh <script> [args]` runs one of these PowerShell scripts from that local copy, under the
  normal `RemoteSigned` policy.
- `screenshot.ps1 [-Out file.png] [-Title text]`: whole desktop, or one window by title.
- `windows.ps1 [-Title text]`: visible top-level windows with process and bounds.
- `focus.ps1 -Title text [-Maximize]`: bring a window to the front (maximized on request).
- `keys.ps1 -Keys "{DOWN}{ENTER}"`: send keys to the foreground window (`SendKeys` syntax).
- `click.ps1 -X 100 -Y 200 [-Right] [-Double]`: click at screen coordinates.

`WIN_ROOT` overrides the workspace (default `C:\dev\kloudstream`).
