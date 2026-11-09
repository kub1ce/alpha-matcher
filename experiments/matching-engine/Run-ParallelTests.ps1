#Requires -Version 7.4
[CmdletBinding()]
param([switch]$Benchmark,[switch]$LargeBenchmark,[switch]$CatalogProbe,[switch]$QualityStage4,[switch]$Stage4Reverse,[switch]$QualityStage4B)
$ErrorActionPreference = 'Stop'
$moduleRoot = $PSScriptRoot
$compose = Join-Path $moduleRoot 'parallel/compose.yaml'
$composeFiles = @('-f',$compose)
if ($Stage4Reverse) { $QualityStage4 = $true }
if ($QualityStage4B -and ($QualityStage4 -or $Benchmark -or $LargeBenchmark -or $CatalogProbe)) { throw "Choose Stage4B alone" }
if ($LargeBenchmark -or $CatalogProbe -or $QualityStage4 -or $QualityStage4B) { $composeFiles += @('-f',(Join-Path $moduleRoot 'parallel/large.yaml')) }
if ($CatalogProbe) { $composeFiles += @('-f',(Join-Path $moduleRoot 'parallel/probe.yaml')) }
if ($QualityStage4) { $composeFiles += @('-f',(Join-Path $moduleRoot 'stage4/compose.yaml')) }
if ($QualityStage4B) { $composeFiles += @('-f',(Join-Path $moduleRoot 'stage4b/compose.yaml')) }
if ($Stage4Reverse) { $composeFiles += @('-f',(Join-Path $moduleRoot 'stage4/reverse.yaml')) }
$wrapper = Join-Path $moduleRoot '../../mvnw.cmd'
$saved = @{}
foreach ($key in @('MATCHING_DB_URL','MATCHING_DB_USER','MATCHING_DB_PASSWORD','MATCHING_PROFILE_ROOT_PASSWORD','MATCHING_PARALLEL_OUTPUT')) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$started = $false
function Save-TestEvidence([string]$label) {
    $folder = Join-Path $env:MATCHING_PARALLEL_OUTPUT $label
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $counts = [ordered]@{tests=0;failures=0;errors=0;skipped=0}
    Get-ChildItem (Join-Path $moduleRoot 'target/surefire-reports'),(Join-Path $moduleRoot 'target/failsafe-reports') -Filter 'TEST-*.xml' | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $folder
        [xml]$document = Get-Content -LiteralPath $_.FullName -Raw
        foreach ($key in @('tests','failures','errors','skipped')) { $counts[$key] += [int]$document.testsuite.$key }
    }
    $counts | ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $folder 'totals.json') -Encoding utf8
    Write-Output ($counts | ConvertTo-Json -Compress)
}
try {
    docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker unavailable' }
    $existing = docker ps -aq --filter 'label=com.docker.compose.project=alpha-matcher-parallel-cpu'
    if ($LASTEXITCODE -ne 0 -or $existing) { throw 'Cannot start: profiling containers already exist or inspection failed' }
    $env:MATCHING_DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $env:MATCHING_PROFILE_ROOT_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $env:MATCHING_DB_USER = 'matcher'
    $env:MATCHING_DB_URL = 'jdbc:mysql://127.0.0.1:33079/matching_engine_exp?characterEncoding=UTF-8&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=15000&allowPublicKeyRetrieval=true&sslMode=DISABLED'
    $env:MATCHING_PARALLEL_OUTPUT = Join-Path $moduleRoot ('parallel/runs/' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
    New-Item -ItemType Directory -Path $env:MATCHING_PARALLEL_OUTPUT -Force | Out-Null
    $started = $true
    docker compose @composeFiles up -d --wait --wait-timeout 180 mysql
    if ($LASTEXITCODE -ne 0) { throw 'Profiling MySQL startup failed' }
    $limits = docker inspect alpha-matcher-parallel-cpu-mysql-1 --format 'cpuset={{.HostConfig.CpusetCpus}} memory={{.HostConfig.Memory}} quota={{.HostConfig.CpuQuota}} nanoCpus={{.HostConfig.NanoCpus}}'
    $limits | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'mysql-docker-limits.txt') -Encoding utf8
    $effective = docker exec alpha-matcher-parallel-cpu-mysql-1 sh /capture-cgroup.sh
    if ($LASTEXITCODE -ne 0 -or ($effective -join "`n") -notmatch 'cpuset.cpus.effective=0-1;') { throw 'MySQL shared cpuset not confirmed' }
    $effective | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'mysql-startup-cgroup.txt') -Encoding utf8
    docker exec alpha-matcher-parallel-cpu-mysql-1 df -k /var/lib/mysql | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'mysql-disk-before.txt') -Encoding utf8
    [ordered]@{timestamp=(Get-Date -Format o);cpu=@(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors,LoadPercentage);docker=(docker info --format 'version={{.ServerVersion}} cpus={{.NCPU}} memory={{.MemTotal}}')} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'host.json') -Encoding utf8
    Push-Location -LiteralPath $moduleRoot
    try {
        & $wrapper -f pom.xml -q -Pmysql-it verify
        $testExit = $LASTEXITCODE
        Save-TestEvidence 'tests-before'
        if ($testExit -ne 0) { throw 'Parallel tests failed; evidence retained' }
        if ($Benchmark -or $LargeBenchmark -or $CatalogProbe -or $QualityStage4 -or $QualityStage4B) {
            if ($QualityStage4B) {
                $baselineArgs = @(Get-ChildItem stage4b/baseline-source/src/main/java -Recurse -Filter '*.java' | ForEach-Object { [IO.Path]::GetRelativePath($moduleRoot,$_.FullName).Replace('\','/') })
                $baselineArgs | Set-Content -LiteralPath stage4b/baseline-sources.args -Encoding utf8NoBOM
                & javac -encoding UTF-8 -d target/stage4b-baseline-classes '@stage4b/baseline-sources.args'
                if ($LASTEXITCODE -ne 0) { throw 'Stage4A baseline compilation failed' }
                foreach ($classes in @('target/stage4b-baseline-classes','target/classes')) {
                    & javac -encoding UTF-8 -cp $classes -d $classes stage4b/Stage4BQualityMain.java
                    if ($LASTEXITCODE -ne 0) { throw 'Stage4B harness compilation failed' }
                }
            }
            if ($QualityStage4) {
                $baselineArgs = @(Get-ChildItem stage4/baseline-source/src/main/java -Recurse -Filter '*.java' | ForEach-Object { [IO.Path]::GetRelativePath($moduleRoot,$_.FullName).Replace('\','/') })
                $baselineArgs | Set-Content -LiteralPath stage4/baseline-sources.args -Encoding utf8NoBOM
                & javac -encoding UTF-8 -d target/stage4-baseline-classes '@stage4/baseline-sources.args'
                if ($LASTEXITCODE -ne 0) { throw 'Frozen baseline compilation failed' }
                & javac -encoding UTF-8 -cp target/stage4-baseline-classes -d target/stage4-baseline-classes stage4/Stage4QualityMain.java
                if ($LASTEXITCODE -ne 0) { throw 'Baseline harness compilation failed' }
                & javac -encoding UTF-8 -cp target/classes -d target/classes stage4/Stage4QualityMain.java
                if ($LASTEXITCODE -ne 0) { throw 'Current harness compilation failed' }
                if ($Stage4Reverse) {
                    & javac -encoding UTF-8 -cp target/classes -d target/classes stage4/ParserABMain.java
                    if ($LASTEXITCODE -ne 0) { throw 'Parser probe compilation failed' }
                }
            }
            if ($CatalogProbe) {
                & javac -encoding UTF-8 -cp target/classes -d target/classes parallel/CatalogProbeMain.java parallel/ReferenceLookupMain.java
                if ($LASTEXITCODE -ne 0) { throw 'Catalog probe compilation failed' }
            }
            & $wrapper -f pom.xml -q 'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:copy-dependencies' '-DincludeScope=runtime'
            if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare runtime dependencies' }
            docker compose @composeFiles up -d app
            if ($LASTEXITCODE -ne 0) { throw 'CPU application startup failed' }
            docker inspect alpha-matcher-parallel-cpu-app-1 --format 'cpuset={{.HostConfig.CpusetCpus}} memory={{.HostConfig.Memory}} quota={{.HostConfig.CpuQuota}} nanoCpus={{.HostConfig.NanoCpus}} image={{.Config.Image}}' | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'java-docker-limits.txt') -Encoding utf8
            $deadline = [DateTime]::UtcNow.AddMinutes($(if ($LargeBenchmark) {120} else {30}))
            $ticks = 0
            while ($true) {
                foreach ($request in Get-ChildItem -LiteralPath $env:MATCHING_PARALLEL_OUTPUT -Filter '*.request') {
                    if ($request.BaseName -notmatch '^(start|end)-([a-z0-9-]+)$') { throw 'Invalid collector request' }
                    $kind=$Matches[1];$phase=$Matches[2]
                    $ack=Join-Path $env:MATCHING_PARALLEL_OUTPUT ($request.BaseName+'.ack')
                    if (Test-Path -LiteralPath $ack) { continue }
                    $data = docker exec alpha-matcher-parallel-cpu-mysql-1 sh /capture-cgroup.sh
                    if ($LASTEXITCODE -ne 0) { throw 'MySQL cgroup sample failed' }
                    $data | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT ("mysql-$kind-$phase.txt")) -Encoding utf8
                    "$phase`t$kind`t$([Diagnostics.Stopwatch]::GetTimestamp())`t$([Diagnostics.Stopwatch]::Frequency)`t$(Get-Date -Format o)" | Add-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'host-samples.tsv') -Encoding utf8
                    New-Item -ItemType File -Path $ack | Out-Null
                    Write-Output "Collector: $kind $phase"
                }
                if ($ticks++ % 5 -eq 0) {
                    $alive=docker inspect alpha-matcher-parallel-cpu-app-1 --format '{{.State.Running}}'
                    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect application lifecycle' }
                    if ($alive -eq 'false') { break }
                }
                if ([DateTime]::UtcNow -gt $deadline) { throw 'CPU experiment exceeded orchestration deadline' }
                Start-Sleep -Milliseconds 100
            }
            docker logs alpha-matcher-parallel-cpu-app-1 2>&1 | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'application.log') -Encoding utf8
            $exitCode=docker inspect alpha-matcher-parallel-cpu-app-1 --format '{{.State.ExitCode}}'
            if ($exitCode -ne '0') { throw "CPU experiment failed with exit code $exitCode; all output retained" }
            if (-not (Test-Path -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'COMPLETED.txt'))) { throw 'CPU experiment did not complete' }
            docker exec alpha-matcher-parallel-cpu-mysql-1 df -k /var/lib/mysql | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'mysql-disk-after.txt') -Encoding utf8
            & $wrapper -f pom.xml -q -Pmysql-it verify
            $testExit=$LASTEXITCODE
            Save-TestEvidence 'tests-after'
            if ($testExit -ne 0) { throw 'Post-benchmark tests failed' }
        }
    } finally { Pop-Location }
} finally {
    if ($started) {
        $app = docker ps -aq --filter 'name=^alpha-matcher-parallel-cpu-app-1$'
        if ($app) { docker logs $app 2>&1 | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'application.log') -Encoding utf8 }
        $db = docker ps -q --filter 'name=^alpha-matcher-parallel-cpu-mysql-1$'
        if ($db) {
            docker exec $db sh /capture-cgroup.sh | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'mysql-final-cgroup.txt') -Encoding utf8
            docker exec $db df -k /var/lib/mysql | Set-Content -LiteralPath (Join-Path $env:MATCHING_PARALLEL_OUTPUT 'mysql-disk-final.txt') -Encoding utf8
        }
        docker compose @composeFiles down --volumes
    }
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
}


