param([switch]$Verify)
$ErrorActionPreference='Stop'
$repo7=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$wrapper7=Join-Path $repo7 'mvnw.cmd'
$engine7=(Resolve-Path (Join-Path $PSScriptRoot '../target/matching-engine-0.1-SNAPSHOT.jar')).Path
$enginePom7=(Resolve-Path (Join-Path $PSScriptRoot '../pom.xml')).Path
& $wrapper7 -q install:install-file ('-Dfile='+$engine7) ('-DpomFile='+$enginePom7)
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$build7=@('-q','-f',(Join-Path $PSScriptRoot 'pom.xml'))
if($Verify){$build7+=@('test','package')}else{$build7+=@('-DskipTests','package')}
& $wrapper7 @build7
exit $LASTEXITCODE
