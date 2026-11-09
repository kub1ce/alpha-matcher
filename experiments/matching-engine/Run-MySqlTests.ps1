[CmdletBinding()]
param([switch]$BatchQuality,[switch]$Stage2,[ValidateSet('baseline','development','final')][string]$Stage3Phase)
$ErrorActionPreference = 'Stop'
if ($BatchQuality -and ($Stage2 -or $Stage3Phase)) { throw 'BatchQuality must be run alone' }
if ($Stage2 -and $Stage3Phase) { throw 'Choose Stage2 or Stage3Phase, not both.' }
$moduleRoot = $PSScriptRoot
$composeFile = Join-Path $moduleRoot 'compose.yaml'
$composeArgs = @('-f',$composeFile)
if ($BatchQuality) { $composeArgs += @('-f',(Join-Path $moduleRoot 'stage4batch/quality-compose.yaml')) }
$wrapper = Join-Path $moduleRoot '../../mvnw.cmd'
$previousEnvironment = @{}
foreach ($name in @('MATCHING_DB_PASSWORD', 'MATCHING_DB_URL', 'MATCHING_DB_USER')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$started = $false
try {
    docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is unavailable. Start Docker Desktop.' }
    $existing = docker ps -aq --filter 'label=com.docker.compose.project=alpha-matcher-engine-exp'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the experiment containers.' }
    if ($existing) { throw 'Experiment containers already exist. They were left untouched. Stop your experiment explicitly before this isolated run.' }
    $env:MATCHING_DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $env:MATCHING_DB_USER = 'matcher'
    $env:MATCHING_DB_URL = 'jdbc:mysql://127.0.0.1:33077/matching_engine_exp?characterEncoding=UTF-8&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=15000&allowPublicKeyRetrieval=true&sslMode=DISABLED'
    $started = $true
    docker compose @composeArgs up -d --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Disposable MySQL did not become healthy.' }
    & $wrapper -f (Join-Path $moduleRoot 'pom.xml') -q -Pmysql-it verify
    if ($LASTEXITCODE -ne 0) { throw 'Matching engine verification failed. Inspect target/surefire-reports and target/failsafe-reports.' }
    if ($BatchQuality) {
        Push-Location -LiteralPath $moduleRoot
        try {
            $batchOutput = Join-Path $moduleRoot ('stage4batch/runs/quality-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
            New-Item -ItemType Directory -Path $batchOutput -ErrorAction Stop | Out-Null
            foreach ($folder in @('target/surefire-reports','target/failsafe-reports')) {
                Copy-Item -LiteralPath $folder -Destination $batchOutput -Recurse
            }
            $sources = @(Get-ChildItem stage4batch/baseline-4b1/src/main/java -Recurse -Filter '*.java' | ForEach-Object { [IO.Path]::GetRelativePath($moduleRoot,$_.FullName).Replace('\','/') })
            $sources | Set-Content stage4batch/baseline-sources.args -Encoding utf8NoBOM
            & javac -encoding UTF-8 -d target/batch-baseline-classes '@stage4batch/baseline-sources.args'
            if ($LASTEXITCODE -ne 0) { throw 'Frozen 4B.1 compilation failed' }
            foreach ($classes in @('target/batch-baseline-classes','target/classes')) {
                & javac -encoding UTF-8 -cp $classes -d $classes stage4batch/Stage4BatchQualityMain.java
                if ($LASTEXITCODE -ne 0) { throw 'Batch quality harness compilation failed' }
            }
            & $wrapper -f pom.xml -q 'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:copy-dependencies' '-DincludeScope=runtime'
            if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare JDBC driver' }
            & java "-Dbatch.output=$batchOutput" -cp 'target/batch-baseline-classes;target/dependency/*' ru.urfu.alphamatcher.experiment.Stage4BatchQualityMain before
            if ($LASTEXITCODE -ne 0) { throw 'Frozen quality replay failed' }
            & java "-Dbatch.output=$batchOutput" -cp 'target/classes;target/dependency/*' ru.urfu.alphamatcher.experiment.Stage4BatchQualityMain after
            if ($LASTEXITCODE -ne 0) { throw 'Current quality gate failed' }
            Write-Output "Batch quality evidence: $batchOutput"
        } finally { Pop-Location }
    }
    if ($Stage3Phase) {
        Push-Location -LiteralPath $moduleRoot
        try {
            $stage3Pom = if ($Stage3Phase -eq 'baseline') { 'stage3/baseline-pom.xml' } else { 'pom.xml' }
            & $wrapper -f $stage3Pom -q compile exec:java '-Dexec.mainClass=ru.urfu.alphamatcher.experiment.Stage3QualityMain' "-Dexec.args=$Stage3Phase"
            if ($LASTEXITCODE -ne 0) { throw "Stage 3 quality run failed: $Stage3Phase" }
        } finally { Pop-Location }
    }
    if ($Stage2) {
        $runRoot = Join-Path $moduleRoot 'runs'
        New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
        $hostInfo = [ordered]@{
            Timestamp = (Get-Date -Format o)
            HostCpu = @(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors)
            DockerResources = (docker info --format 'cpus={{.NCPU}} memoryBytes={{.MemTotal}}')
            ContainerCpuLimits = (docker inspect alpha-matcher-engine-exp-mysql-1 --format 'NanoCpus={{.HostConfig.NanoCpus}} CpusetCpus={{.HostConfig.CpusetCpus}} CpuQuota={{.HostConfig.CpuQuota}}')
            CpuRestrictionApplied = $false
        }
        $hostInfo | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runRoot "host-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()).json") -Encoding utf8
        Push-Location -LiteralPath $moduleRoot
        try {
            foreach ($configName in @('simple-1000', 'stress-1000', 'mixed-1000', 'mixed-10000')) {
                & $wrapper -f stage3/baseline-pom.xml -q compile exec:java '-Dexec.mainClass=ru.urfu.alphamatcher.experiment.BenchmarkMain' "-Dexec.args=config/$configName.properties"
                if ($LASTEXITCODE -ne 0) { throw "Stage 2 run failed: $configName" }
            }
        } finally { Pop-Location }
    }
} finally {
    if ($started) {
        docker compose @composeArgs down --volumes
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Could not stop the disposable experiment containers. Stop them explicitly.' }
    }
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
