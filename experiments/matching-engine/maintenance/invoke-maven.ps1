param([Parameter(Mandatory=$true)][string]$Wrapper,[Parameter(Mandatory=$true)][string]$Pom)
$ErrorActionPreference='Stop'
& $Wrapper -q -f $Pom test package
exit $LASTEXITCODE
