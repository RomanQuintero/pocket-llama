# Download GGUF models (all from one source: unsloth/Qwen3-*-GGUF, quant Q4_K_M) and record SHA256.
param([string[]]$Sizes = @("0.6B"))
Set-Location "$env:USERPROFILE\Desktop\30-Projects\18"
New-Item -ItemType Directory -Force models | Out-Null
foreach ($s in $Sizes) {
  $name = "Qwen3-$s-Q4_K_M.gguf"
  $url  = "https://huggingface.co/unsloth/Qwen3-$s-GGUF/resolve/main/$name"
  $dst  = "models\$name"
  if (-not (Test-Path $dst)) {
    "downloading $url"
    curl.exe -L --fail --retry 3 -o "$dst.part" $url 2>&1 | Select-Object -Last 2
    if ($LASTEXITCODE -ne 0) { "FAILED $name"; continue }
    Move-Item "$dst.part" $dst -Force
  }
  $h = (Get-FileHash $dst -Algorithm SHA256).Hash
  "$name`t$((Get-Item $dst).Length)`t$h`t$url" | Tee-Object -Append -FilePath "models\SHA256SUMS.tsv"
}
