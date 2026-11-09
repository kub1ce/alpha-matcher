$ErrorActionPreference="Stop"
& python -X utf8 -B (Join-Path $PSScriptRoot "demo_runtime.py") stop
if($LASTEXITCODE -ne 0){throw "Stage7 stop request failed"}
