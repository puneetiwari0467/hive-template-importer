[CmdletBinding()]
param(
    [string]$ResourceGroup = "rg-hive-template-importer",
    [Parameter(Mandatory)][string]$AppName,
    [string]$JarPath = ""
)

$ErrorActionPreference = "Stop"
if (-not $JarPath) {
    $JarPath = [IO.Path]::Combine([IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..")),
        "backend", "target", "app.jar")
}
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw "Build the complete application first; the deployable JAR was not found."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($JarPath))
try {
    foreach ($entry in @(
        "BOOT-INF/classes/static/index.html",
        "BOOT-INF/classes/sample-data/Room-by-Room Residential Template-2026-09-18.xls"
    )) {
        if (-not $jar.GetEntry($entry)) { throw "Deployment artifact is incomplete: missing $entry." }
    }
} finally {
    $jar.Dispose()
}

$project = & az group show -n $ResourceGroup --query tags.project -o tsv --only-show-errors
if ($LASTEXITCODE -ne 0 -or $project -ne "hive-template-importer") {
    throw "Refusing to deploy into an unrelated or inaccessible resource group."
}
$hostName = & az webapp show -g $ResourceGroup -n $AppName --query defaultHostName -o tsv --only-show-errors
if ($LASTEXITCODE -ne 0 -or -not $hostName) { throw "Could not resolve the target web app." }

& az webapp deploy -g $ResourceGroup -n $AppName --src-path $JarPath --type jar `
    --async false --track-status true --restart true --timeout 600000 --only-show-errors -o none
if ($LASTEXITCODE -ne 0) {
    throw "Azure did not confirm deployment. Inspect this app's deployment logs before retrying; do not modify another service."
}

$base = "https://$hostName"
$healthy = $false
for ($attempt = 1; $attempt -le 12; $attempt++) {
    try {
        $health = Invoke-RestMethod "$base/api/health" -TimeoutSec 15
        if ($health.status -eq "UP") {
            $healthy = $true
            break
        }
        Write-Warning "Health attempt $attempt did not report UP."
    } catch [System.Net.Http.HttpRequestException], [System.Net.WebException], [System.Threading.Tasks.TaskCanceledException] {
        Write-Warning "Health attempt $attempt failed: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds 5
}
if (-not $healthy) { throw "Deployment was uploaded, but the database-backed health check is not ready." }

$page = Invoke-WebRequest "$base/" -TimeoutSec 30
if ($page.Content -notmatch 'id=["'']root["'']' -or $page.Content -notmatch '/assets/') {
    throw "The API is healthy, but the React production index was not served."
}
Write-Output "Verified API health and React index: $base"
Write-Output "Next: run scripts/api-smoke.mjs against this URL and verify the browser workflow."
