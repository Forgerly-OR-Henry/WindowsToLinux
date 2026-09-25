$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class BrowserPrivacyNative {
    public delegate bool WindowCallback(IntPtr window, IntPtr state);
    [DllImport("user32.dll")] public static extern bool EnumWindows(WindowCallback callback, IntPtr state);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr window);
    [DllImport("user32.dll")] public static extern bool EnableWindow(IntPtr window, bool enabled);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr window, int state);
}
'@
if ($privacyAction -eq 'restore') {
    foreach ($entry in $restoreHandles.Split(';', [StringSplitOptions]::RemoveEmptyEntries)) {
        $parts = $entry.Split(':')
        $window = [IntPtr][long]$parts[0]
        [uint32]$ownerProcess = 0
        [void][BrowserPrivacyNative]::GetWindowThreadProcessId($window, [ref]$ownerProcess)
        if ($ownerProcess -eq $browserProcessId) {
            [void][BrowserPrivacyNative]::EnableWindow($window, $true)
            $showMode = if ($parts[1] -eq '1') { 7 } else { 4 }
            [void][BrowserPrivacyNative]::ShowWindow($window, $showMode)
        }
    }
} else {
    $windows = [System.Collections.Generic.List[string]]::new()
    $callback = [BrowserPrivacyNative+WindowCallback]{
        param([IntPtr]$window, [IntPtr]$state)
        [uint32]$ownerProcess = 0
        [void][BrowserPrivacyNative]::GetWindowThreadProcessId($window, [ref]$ownerProcess)
        if ($ownerProcess -eq $browserProcessId -and [BrowserPrivacyNative]::IsWindowVisible($window)) {
            $iconic = [int][BrowserPrivacyNative]::IsIconic($window)
            $windows.Add("$($window.ToInt64()):$iconic")
            if ($privacyAction -eq 'hide') {
                [void][BrowserPrivacyNative]::EnableWindow($window, $false)
                [void][BrowserPrivacyNative]::ShowWindow($window, 0)
            }
        }
        return $true
    }
    [void][BrowserPrivacyNative]::EnumWindows($callback, [IntPtr]::Zero)
    $windows | ForEach-Object { [Console]::WriteLine($_) }
}
