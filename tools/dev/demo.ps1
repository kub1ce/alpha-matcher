$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location -LiteralPath $repository
try {
    & .\mvnw.cmd '-Dspring-boot.run.profiles=demo' '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8080' spring-boot:run
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
