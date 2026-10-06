# Phase 0 - read-only environment inventory (installs nothing)
$out = Join-Path $PSScriptRoot "..\results\raw\phase0-inventory.txt"
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
Start-Transcript -Path $out -Force | Out-Null
function H($t){ Write-Output "`n===== $t =====" }
H "Env vars"
"ANDROID_HOME=$env:ANDROID_HOME"; "ANDROID_SDK_ROOT=$env:ANDROID_SDK_ROOT"; "ANDROID_NDK_HOME=$env:ANDROID_NDK_HOME"; "JAVA_HOME=$env:JAVA_HOME"
H "Commands on PATH"
foreach($c in "adb","java","javac","gradle","cmake","ninja","git","python","sdkmanager"){ $p=(Get-Command $c -ErrorAction SilentlyContinue).Source; "{0,-10} {1}" -f $c,$p }
$sdk = @($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,"$env:LOCALAPPDATA\Android\Sdk") | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
H "Android SDK: $sdk"
if($sdk){
  foreach($d in "platform-tools","platforms","build-tools","ndk","cmake","cmdline-tools"){ "--- $d"; Get-ChildItem "$sdk\$d" -ErrorAction SilentlyContinue | ForEach-Object Name }
  if(Test-Path "$sdk\ndk-bundle"){ "--- ndk-bundle present" }
  $adb = "$sdk\platform-tools\adb.exe"
}
if(-not $adb -or -not (Test-Path $adb)){ $adb=(Get-Command adb -ErrorAction SilentlyContinue).Source }
H "Versions"
if($adb){ & $adb version }
java -version 2>&1
Get-ChildItem "C:\Program Files\Android\Android Studio\jbr\bin\java.exe","C:\Program Files\Java","C:\Program Files\Eclipse Adoptium" -ErrorAction SilentlyContinue | ForEach-Object FullName
cmake --version 2>&1 | Select-Object -First 1
git --version 2>&1
H "Gradle caches"
Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -ErrorAction SilentlyContinue | ForEach-Object Name
H "Devices"
if($adb){
  & $adb devices -l
  foreach($p in "ro.product.manufacturer","ro.product.model","ro.product.device","ro.build.version.release","ro.build.version.sdk","ro.product.cpu.abi","ro.product.cpu.abilist","ro.soc.manufacturer","ro.soc.model","ro.board.platform","ro.build.display.id"){ "{0} = {1}" -f $p,(& $adb shell getprop $p) }
  H "Device memory"; & $adb shell cat /proc/meminfo | Select-Object -First 6
  H "Device storage"; & $adb shell df -h /data
  H "Device CPU"; & $adb shell "cat /proc/cpuinfo | grep -E 'processor|CPU part|Hardware' "
  & $adb shell "for c in /sys/devices/system/cpu/cpu[0-9]*; do echo \$c \$(cat \$c/cpufreq/cpuinfo_max_freq 2>/dev/null); done"
  H "Battery / thermal"; & $adb shell dumpsys battery; & $adb shell dumpsys thermalservice | Select-Object -First 25
}
Stop-Transcript | Out-Null
Write-Host "`nGuardado en $out" -ForegroundColor Green
