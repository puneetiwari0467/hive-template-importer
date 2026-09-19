[CmdletBinding()]
param(
    [string]$ResourceGroup = "rg-hive-template-importer",
    [string]$Location = "centralindia",
    [string]$DatabaseLocation = "",
    [string]$DatabaseServerName = "",
    [Parameter(Mandatory)][string]$Prefix,
    [Parameter(Mandatory)][string]$PrivateOutputDirectory
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($DatabaseLocation)) { $DatabaseLocation = $Location }

function Invoke-Azure {
    param([string[]]$Arguments)
    $result = & az @Arguments --only-show-errors
    if ($LASTEXITCODE -ne 0) {
        throw "Azure command failed: $($Arguments[0]) $($Arguments[1]). Check the error above."
    }
    return $result
}

if ($Prefix -notmatch '^[a-z][a-z0-9-]{5,30}$') {
    throw "Prefix must be 6-31 lowercase letters, numbers, or hyphens, starting with a letter."
}
if (-not [IO.Path]::IsPathFullyQualified($PrivateOutputDirectory)) {
    throw "PrivateOutputDirectory must be an absolute path outside the repository."
}
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$privatePath = [IO.Path]::GetFullPath($PrivateOutputDirectory)
if ([string]::Equals($privatePath.TrimEnd([IO.Path]::DirectorySeparatorChar), $repository, [StringComparison]::OrdinalIgnoreCase) -or
    $privatePath.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Credentials must not be written inside the repository."
}

Invoke-Azure @("account", "show", "--query", "{subscription:name,state:state}", "-o", "json")
$registration = Invoke-Azure @("provider", "show", "-n", "Microsoft.DBforMySQL",
    "--query", "registrationState", "-o", "tsv")
if ($registration -ne "Registered") {
    Write-Output "Registering the MySQL resource provider for this subscription."
    Invoke-Azure @("provider", "register", "-n", "Microsoft.DBforMySQL", "--wait", "-o", "none")
}
New-Item -ItemType Directory -Path $privatePath -Force | Out-Null
if ($IsWindows) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls $privatePath /inheritance:r /grant:r ($identity + ":(OI)(CI)F") | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not restrict credential directory permissions." }
} else {
    & chmod 700 $privatePath
    if ($LASTEXITCODE -ne 0) { throw "Could not restrict credential directory permissions." }
}

$statePath = Join-Path $privatePath "azure-resources.json"
$plan = "asp-$Prefix"
$server = if ($DatabaseServerName) { $DatabaseServerName } else { "$Prefix-mysql" }
if ($server -notmatch '^[a-z][a-z0-9-]{2,62}$') { throw "Invalid MySQL server name." }
$exists = Invoke-Azure @("group", "exists", "-n", $ResourceGroup, "-o", "tsv")
if ($exists -eq "true") {
    $owner = Invoke-Azure @("group", "show", "-n", $ResourceGroup, "--query", "tags.project", "-o", "tsv")
    if ($owner -ne "hive-template-importer") {
        throw "Existing resource group is not tagged for this project; refusing to change it."
    }
} else {
    Invoke-Azure @("group", "create", "-n", $ResourceGroup, "-l", $Location,
        "--tags", "project=hive-template-importer", "purpose=take-home-demo", "-o", "none")
}

if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.resourceGroup -ne $ResourceGroup -or $state.appName -ne $Prefix) {
        throw "Saved provisioning state belongs to a different deployment."
    }
    if (-not $DatabaseServerName) { $server = $state.serverName }
} else {
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(30)).
        Replace("+", "A").Replace("/", "B").TrimEnd("=") + "aA1!"
    $state = [pscustomobject]@{
        resourceGroup = $ResourceGroup
        location = $Location
        planName = $plan
        appName = $Prefix
        serverName = $server
        databaseName = "hive_importer"
        databaseLocation = $DatabaseLocation
        adminUsername = "hiveadmin"
        adminPassword = $password
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    if (-not $IsWindows) { & chmod 600 $statePath }
}

$plans = Invoke-Azure @("appservice", "plan", "list", "-g", $ResourceGroup, "--query", "[].name", "-o", "tsv")
if ($plan -notin @($plans)) {
    Write-Output "Creating isolated Linux B2 plan."
    Invoke-Azure @("appservice", "plan", "create", "-g", $ResourceGroup, "-n", $plan,
        "-l", $Location, "--is-linux", "--sku", "B2", "--number-of-workers", "1",
        "--tags", "project=hive-template-importer", "-o", "none")
}

$apps = Invoke-Azure @("webapp", "list", "-g", $ResourceGroup, "--query", "[].name", "-o", "tsv")
if ($Prefix -notin @($apps)) {
    Write-Output "Creating Java 21 web app."
    $subscription = Invoke-Azure @("account", "show", "--query", "id", "-o", "tsv")
    $planId = Invoke-Azure @("appservice", "plan", "show", "-g", $ResourceGroup,
        "-n", $plan, "--query", "id", "-o", "tsv")
    $accessToken = Invoke-Azure @("account", "get-access-token",
        "--resource", "https://management.azure.com/", "--query", "accessToken", "-o", "tsv")
    $site = @{
        location = $Location
        kind = "app,linux"
        tags = @{ project = "hive-template-importer" }
        properties = @{
            reserved = $true
            serverFarmId = $planId
            httpsOnly = $true
            siteConfig = @{
                linuxFxVersion = "JAVA|21-java21"
                alwaysOn = $true
                minTlsVersion = "1.2"
                ftpsState = "Disabled"
                http20Enabled = $true
            }
        }
    } | ConvertTo-Json -Depth 6
    # Some CLI runtime catalogs omit current Java SE stacks; configure the documented ARM property.
    $siteUri = "https://management.azure.com/subscriptions/$subscription/resourceGroups/$ResourceGroup/providers/Microsoft.Web/sites/$Prefix`?api-version=2024-11-01"
    Invoke-RestMethod -Method Put -Uri $siteUri -Headers @{
        Authorization = "Bearer $accessToken"
    } -ContentType "application/json" -Body $site -TimeoutSec 180 | Out-Null
}
Invoke-Azure @("webapp", "update", "-g", $ResourceGroup, "-n", $Prefix,
    "--https-only", "true", "-o", "none")
Invoke-Azure @("webapp", "config", "set", "-g", $ResourceGroup, "-n", $Prefix,
    "--always-on", "true", "--min-tls-version", "1.2", "--ftps-state", "Disabled",
    "--http20-enabled", "true", "-o", "none")

$servers = Invoke-Azure @("mysql", "flexible-server", "list", "-g", $ResourceGroup, "--query", "[].name", "-o", "tsv")
if ($server -notin @($servers)) {
    Write-Output "Creating separate MySQL B1ms server in $DatabaseLocation; this can take several minutes."
    Invoke-Azure @("mysql", "flexible-server", "create", "-g", $ResourceGroup, "-n", $server,
        "-l", $DatabaseLocation, "--tier", "Burstable", "--sku-name", "Standard_B1ms",
        "--version", "8.4", "--storage-size", "20", "--storage-auto-grow", "Disabled",
        "--auto-scale-iops", "Disabled", "--high-availability", "Disabled",
        "--geo-redundant-backup", "Disabled", "--backup-retention", "7",
        "--admin-user", $state.adminUsername, "--admin-password", $state.adminPassword,
        "--public-access", "None", "--database-name", $state.databaseName,
        "--tags", "project=hive-template-importer", "--yes", "-o", "none")
}

$hostName = Invoke-Azure @("webapp", "show", "-g", $ResourceGroup, "-n", $Prefix,
    "--query", "defaultHostName", "-o", "tsv")
$mysqlHost = Invoke-Azure @("mysql", "flexible-server", "show", "-g", $ResourceGroup, "-n", $server,
    "--query", "fullyQualifiedDomainName", "-o", "tsv")
$actualDatabaseLocation = Invoke-Azure @("mysql", "flexible-server", "show", "-g", $ResourceGroup,
    "-n", $server, "--query", "location", "-o", "tsv")
$state | Add-Member -NotePropertyName appHost -NotePropertyValue $hostName -Force
$state | Add-Member -NotePropertyName mysqlHost -NotePropertyValue $mysqlHost -Force
$state | Add-Member -NotePropertyName serverName -NotePropertyValue $server -Force
$state | Add-Member -NotePropertyName databaseLocation -NotePropertyValue $actualDatabaseLocation -Force
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8

$ips = (Invoke-Azure @("webapp", "show", "-g", $ResourceGroup, "-n", $Prefix,
    "--query", "possibleOutboundIpAddresses", "-o", "tsv")) -split ","
$index = 0
foreach ($ip in ($ips | Sort-Object -Unique)) {
    $parsed = $null
    if (-not [Net.IPAddress]::TryParse($ip, [ref]$parsed)) { throw "Invalid outbound IP returned by App Service." }
    Invoke-Azure @("mysql", "flexible-server", "firewall-rule", "create",
        "-g", $ResourceGroup, "-n", $server, "--rule-name", "app-service-$index",
        "--start-ip-address", $ip, "--end-ip-address", $ip, "-o", "none")
    $index++
}

Write-Output "Provisioned app: https://$hostName"
Write-Output "MySQL firewall allows only this App Service's possible outbound IPs."
Write-Output "Private provisioning state saved outside the repository. No credentials were printed."
