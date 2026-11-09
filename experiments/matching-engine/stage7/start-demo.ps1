param([switch]$Verify)
$ErrorActionPreference="Stop"
$entry7=Join-Path $PSScriptRoot "demo_runtime.py"
if($Verify){ & python -X utf8 -B $entry7 start --verify }else{ & python -X utf8 -B $entry7 start }
if($LASTEXITCODE -ne 0){throw "Stage7 launcher failed"}
