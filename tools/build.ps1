# Build the APK (arm64-v8a) and run JVM unit tests with the toolchain already on this machine:
# SDK + NDK 27.2 + CMake 3.22.1 (from the SDK), JDK 21 (Gradle 8.13 cannot run on the Studio JBR 25).
# Output goes to results/raw/build-<timestamp>.log
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
[Environment]::CurrentDirectory = $root          # so child processes (cmd) start here too
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
"sdk.dir=$($sdk -replace '\\','\\')" | Out-File -Encoding ascii local.properties
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"
$log = "results\raw\build-$(Get-Date -Format yyyyMMdd-HHmmss).log"
$t0 = Get-Date
cmd /c "`"$root\gradlew.bat`" --no-daemon --console=plain assembleDebug testDebugUnitTest > `"$log`" 2>&1"
$code = $LASTEXITCODE
"BUILD EXIT $code after $([int]((Get-Date)-$t0).TotalSeconds)s" | Tee-Object -Append -FilePath $log
Get-ChildItem app\build\outputs\apk\debug\*.apk -ErrorAction SilentlyContinue | ForEach-Object { "$($_.FullName) $($_.Length)" } | Tee-Object -Append -FilePath $log
exit $code
