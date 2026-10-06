# Phase 0b - device-only details (read-only)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path $PSScriptRoot "..\results\raw\phase0-device.txt"
$cmds = @(
 "version", "devices -l",
 "shell df -h /data /sdcard",
 "shell cat /proc/cpuinfo",
 "shell cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq",
 "shell cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq",
 "shell cat /proc/meminfo",
 "shell dumpsys battery",
 "shell getprop ro.config.low_ram",
 "shell getprop persist.sys.zram_enabled",
 "shell cat /proc/swaps"
)
$lines = foreach($c in $cmds){ "===== adb $c ====="; (& $adb ($c -split ' ') 2>&1 | Out-String) }
$lines | Out-File -Encoding utf8 $out
Write-Host "Guardado en $out" -ForegroundColor Green
