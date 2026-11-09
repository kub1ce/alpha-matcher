$ErrorActionPreference='Stop'
Set-Location -LiteralPath 'C:\Users\saveliy\Documents\git repo\alpha-matcher'
$args4f=@('-f','C:\Users\saveliy\Documents\git repo\alpha-matcher\experiments\matching-engine\pom.xml','-q','-Pmysql-it','verify')
if($env:STAGE4F_TESTS){$args4f+=('-Dtest='+$env:STAGE4F_TESTS);$args4f+='-Dit.test=SafeDiscoveryMySqlIT,CorrectnessRemediationMySqlIT'}
& 'C:\Users\saveliy\Documents\git repo\alpha-matcher\mvnw.cmd' @args4f
$result4f=$LASTEXITCODE
$out4f=Join-Path $env:STAGE4F_ROOT ('test-evidence/'+$env:STAGE4F_REQUEST)
New-Item -ItemType Directory $out4f -ErrorAction Stop | Out-Null
foreach($name4f in @('surefire-reports','failsafe-reports')){
 $destination4f=Join-Path $out4f $name4f
 New-Item -ItemType Directory $destination4f | Out-Null
 foreach($file4f in Get-ChildItem -LiteralPath (Join-Path 'C:\Users\saveliy\Documents\git repo\alpha-matcher\experiments\matching-engine/target' $name4f) -Filter 'TEST-*.xml'){
  [xml]$xml4f=Get-Content -LiteralPath $file4f.FullName -Raw
  foreach($property4f in @($xml4f.SelectNodes('//properties'))){[void]$property4f.ParentNode.RemoveChild($property4f)}
  $xml4f.Save((Join-Path $destination4f $file4f.Name))
 }
}
exit $result4f
