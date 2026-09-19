[CmdletBinding()]
param([switch]$SkipTests)

$ErrorActionPreference = "Stop"
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$previous = Get-Location

try {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw "Node is not on PATH. Install Node LTS or add its directory to this terminal's PATH."
    }
    $java = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME ("bin" + [IO.Path]::DirectorySeparatorChar + $(if ($IsWindows) { "java.exe" } else { "java" }))
    } else { "java" }
    $javaVersion = & $java -version 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "21[.\-"]') {
        throw "Set JAVA_HOME to a Java 21 JDK. Maven uses JAVA_HOME even when a different java executable appears first on PATH."
    }
    if (-not $SkipTests -and -not $env:TEST_DB_URL) {
        throw "Set TEST_DB_URL, TEST_DB_USERNAME and TEST_DB_PASSWORD for a dedicated MySQL test database. A full verified build must not silently skip integration tests."
    }
    Set-Location (Join-Path $root "frontend")
    & npm ci
    if ($LASTEXITCODE -ne 0) { throw "Frontend dependency installation failed." }
    if (-not $SkipTests) {
        & npm test -- --run
        if ($LASTEXITCODE -ne 0) { throw "Frontend tests failed." }
    }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw "Frontend type check or production build failed." }

    Set-Location (Join-Path $root "backend")
    $wrapper = if ($IsWindows) { ".\mvnw.cmd" } else { "./mvnw" }
    $arguments = @("--batch-mode", "package")
    if ($SkipTests) { $arguments += "-DskipTests" }
    & $wrapper @arguments
    if ($LASTEXITCODE -ne 0) { throw "Backend tests or packaging failed." }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jar = [IO.Compression.ZipFile]::OpenRead([IO.Path]::Combine($root, "backend", "target", "app.jar"))
    try {
        if (-not $jar.GetEntry("BOOT-INF/classes/static/index.html")) {
            throw "The executable JAR is missing the React production build."
        }
        if (-not $jar.GetEntry("BOOT-INF/classes/sample-data/Room-by-Room Residential Template-2026-09-18.xls")) {
            throw "The executable JAR is missing the original sample workbook."
        }
    } finally {
        $jar.Dispose()
    }
    Write-Output "Build verified: backend API, React production assets and source workbook are packaged in backend/target/app.jar."
} finally {
    Set-Location $previous
}
