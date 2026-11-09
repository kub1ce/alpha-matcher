#Requires -Version 7.4
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$moduleRoot = $PSScriptRoot
$compose = Join-Path $moduleRoot 'performance/compose.yaml'
$wrapper = Join-Path $moduleRoot '../../mvnw.cmd'
$saved = @{}
foreach ($key in @('MATCHING_DB_URL','MATCHING_DB_USER','MATCHING_DB_PASSWORD','MATCHING_PROFILE_ROOT_PASSWORD')) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$started = $false
try {
    docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker unavailable' }
    $existing = docker ps -aq --filter 'label=com.docker.compose.project=alpha-matcher-sql-profile'
    if ($LASTEXITCODE -ne 0 -or $existing) { throw 'Cannot start: profiling containers already exist or inspection failed' }
    $env:MATCHING_DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $env:MATCHING_PROFILE_ROOT_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $env:MATCHING_DB_USER = 'matcher'
    $env:MATCHING_DB_URL = 'jdbc:mysql://127.0.0.1:33078/matching_engine_exp?characterEncoding=UTF-8&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=15000&allowPublicKeyRetrieval=true&sslMode=DISABLED'
    $started = $true
    docker compose -f $compose up -d --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Profiling MySQL startup failed' }
    Push-Location -LiteralPath $moduleRoot
    try {
        & $wrapper -f pom.xml -q -Pmysql-it verify
        if ($LASTEXITCODE -ne 0) { throw 'Pre-profile tests failed' }
        & $wrapper -f pom.xml -q compile exec:java '-Dexec.mainClass=ru.urfu.alphamatcher.experiment.SqlProfileMain'
        if ($LASTEXITCODE -ne 0) { throw 'SQL profile failed; preserve partial output' }
        & $wrapper -f pom.xml -q -Pmysql-it verify
        if ($LASTEXITCODE -ne 0) { throw 'Post-profile tests failed' }
    } finally { Pop-Location }
} finally {
    if ($started) { docker compose -f $compose down --volumes }
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
}
