# Download GGUF models (all from one source: unsloth/Qwen3-*-GGUF, quant Q4_K_M) and record SHA256.
# Resumes partial downloads (*.part) and keeps retrying, because the connection drops mid-file.
param([string[]]$Sizes = @("0.6B"), [int]$MaxAttempts = 50)
Set-Location "$env:USERPROFILE\Desktop\30-Projects\18"
New-Item -ItemType Directory -Force models | Out-Null
foreach ($s in $Sizes) {
  $name = "Qwen3-$s-Q4_K_M.gguf"
  $url  = "https://huggingface.co/unsloth/Qwen3-$s-GGUF/resolve/main/$name"
  $dst  = "models\$name"
  if (-not (Test-Path $dst)) {
    "downloading $url"
    $ok = $false
    for ($i = 1; $i -le $MaxAttempts -and -not $ok; $i++) {
      cmd /c "curl.exe -sS -L --fail -C - --retry 5 --retry-all-errors --speed-limit 1024 --speed-time 60 -o `"$dst.part`" $url 2>&1"
      $ok = ($LASTEXITCODE -eq 0)
      "attempt $i exit $LASTEXITCODE, have $([math]::Round((Get-Item "$dst.part" -ErrorAction SilentlyContinue).Length/1MB)) MB"
      if (-not $ok) { Start-Sleep 5 }
    }
    if (-not $ok) { "FAILED $name"; continue }
    Move-Item "$dst.part" $dst -Force
  }
  $h = (Get-FileHash $dst -Algorithm SHA256).Hash
  "$name`t$((Get-Item $dst).Length)`t$h`t$url" | Tee-Object -Append -FilePath "models\SHA256SUMS.tsv"
}
