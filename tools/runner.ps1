# Job runner for Project 18.
# Runs, one at a time, the .ps1 jobs that appear in tools\queue\ and writes their output to
# tools\queue\done\<job>.log. It only executes files inside this project's queue folder.
# Every job stays in tools\queue\done\ so you can read exactly what ran.
# Stop it with Ctrl+C or by creating an empty file tools\queue\STOP.
$ErrorActionPreference = "Continue"
$q    = Join-Path $PSScriptRoot "queue"
$done = Join-Path $q "done"
New-Item -ItemType Directory -Force -Path $done | Out-Null
Write-Host "Runner watching $q  (Ctrl+C to stop)" -ForegroundColor Cyan
while ($true) {
  if (Test-Path (Join-Path $q "STOP")) { Remove-Item (Join-Path $q "STOP"); Write-Host "STOP"; break }
  $job = Get-ChildItem $q -Filter *.ps1 -File | Sort-Object Name | Select-Object -First 1
  if ($job) {
    Start-Sleep -Milliseconds 500   # let the writer finish
    $base = Join-Path $done $job.BaseName
    Move-Item $job.FullName "$base.ps1" -Force
    $t0 = Get-Date
    Write-Host "[$($t0.ToString('HH:mm:ss'))] RUN  $($job.Name)" -ForegroundColor Yellow
    "started $($t0.ToString('o'))" | Out-File -Encoding utf8 "$base.status"
    $p = Start-Process powershell -ArgumentList "-NoProfile","-ExecutionPolicy","Bypass","-File","`"$base.ps1`"" `
         -WorkingDirectory (Split-Path $PSScriptRoot) -NoNewWindow -PassThru `
         -RedirectStandardOutput "$base.log" -RedirectStandardError "$base.err"
    $p.WaitForExit()
    $secs = [int]((Get-Date) - $t0).TotalSeconds
    "exit $($p.ExitCode) after ${secs}s" | Out-File -Encoding utf8 "$base.status"
    Write-Host "[$((Get-Date).ToString('HH:mm:ss'))] DONE $($job.Name) exit=$($p.ExitCode) ${secs}s" -ForegroundColor Green
  }
  Start-Sleep -Seconds 2
}
