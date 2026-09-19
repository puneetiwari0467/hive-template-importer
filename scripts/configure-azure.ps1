[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PrivateStatePath,
    [Parameter(Mandatory)][string]$CaBundle,
    [string]$MySqlExecutable = "mysql",
    [string]$PublicIp = ""
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $CaBundle -PathType Leaf)) {
    throw "A trusted PEM CA bundle is required; TLS verification will not be disabled."
}
$state = Get-Content -LiteralPath $PrivateStatePath -Raw | ConvertFrom-Json
if (-not $state.mysqlHost -or -not $state.appHost) {
    throw "Provisioning has not completed; the private state file has no verified hostnames."
}
if ($state.databaseName -notmatch '^[a-z][a-z0-9_]{0,63}$') { throw "Invalid schema name." }
if ($state.mysqlHost -notmatch '^[a-z0-9-]+\.mysql\.database\.azure\.com$') {
    throw "Unexpected database hostname."
}

function Invoke-Azure {
    param([string[]]$Arguments)
    $result = & az @Arguments --only-show-errors
    if ($LASTEXITCODE -ne 0) { throw "Azure command failed: $($Arguments[0]) $($Arguments[1])." }
    return $result
}

$owner = Invoke-Azure @("group", "show", "-n", $state.resourceGroup, "--query", "tags.project", "-o", "tsv")
if ($owner -ne "hive-template-importer") { throw "Refusing to configure an unrelated resource group." }

if (-not $state.PSObject.Properties["runtimePassword"]) {
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(30)).
        Replace("+", "A").Replace("/", "B").TrimEnd("=") + "aA1!"
    $state | Add-Member -NotePropertyName runtimeUsername -NotePropertyValue "hive_app"
    $state | Add-Member -NotePropertyName runtimePassword -NotePropertyValue $password
    $state | ConvertTo-Json | Set-Content -LiteralPath $PrivateStatePath -Encoding utf8
}
if ($state.runtimeUsername -ne "hive_app" -or $state.runtimePassword -notmatch '^[A-Za-z0-9!]{30,100}$') {
    throw "Unexpected runtime credentials in deployment state."
}

if (-not $PublicIp) { $PublicIp = (Invoke-RestMethod "https://api4.ipify.org" -TimeoutSec 30).Trim() }
$address = $null
if (-not [Net.IPAddress]::TryParse($PublicIp, [ref]$address) -or
    $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
    throw "Database bootstrap requires an explicit public IPv4 address."
}

$rule = "temporary-owner-bootstrap"
$oldPassword = $env:MYSQL_PWD
$firewallCreated = $false
try {
    Invoke-Azure @("mysql", "flexible-server", "firewall-rule", "create",
        "-g", $state.resourceGroup, "-n", $state.serverName, "--rule-name", $rule,
        "--start-ip-address", $PublicIp, "--end-ip-address", $PublicIp, "-o", "none")
    $firewallCreated = $true
    $env:MYSQL_PWD = $state.adminPassword
    $schema = $state.databaseName
    $password = $state.runtimePassword
    $sql = "CREATE USER IF NOT EXISTS 'hive_app'@'%' IDENTIFIED BY '$password'; " +
        "ALTER USER 'hive_app'@'%' IDENTIFIED BY '$password'; " +
        "GRANT ALL PRIVILEGES ON ``$schema``.* TO 'hive_app'@'%';"
    $sql | & $MySqlExecutable --protocol=TCP --host=$($state.mysqlHost) --port=3306 `
        --user=$($state.adminUsername) --ssl-mode=VERIFY_IDENTITY --ssl-ca=$CaBundle `
        --connect-timeout=30 --batch
    if ($LASTEXITCODE -ne 0) { throw "Secure MySQL runtime-user initialization failed." }
} finally {
    $env:MYSQL_PWD = $oldPassword
    if ($firewallCreated) {
        Invoke-Azure @("mysql", "flexible-server", "firewall-rule", "delete",
            "-g", $state.resourceGroup, "-n", $state.serverName, "--rule-name", $rule, "--yes", "-o", "none")
    }
}

$settingsPath = Join-Path (Split-Path -Parent $PrivateStatePath) "app-settings.json"
$settings = @{
    DB_URL = "jdbc:mysql://$($state.mysqlHost):3306/$($state.databaseName)?sslMode=VERIFY_IDENTITY&serverTimezone=UTC&rewriteBatchedStatements=true&connectTimeout=10000&socketTimeout=60000"
    DB_USERNAME = $state.runtimeUsername
    DB_PASSWORD = $state.runtimePassword
    APP_ALLOWED_ORIGINS = "https://$($state.appHost)"
    PORT = "80"
    JAVA_OPTS = "-Xms128m -Xmx1024m -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
    SCM_DO_BUILD_DURING_DEPLOYMENT = "false"
    WEBSITES_CONTAINER_START_TIME_LIMIT = "600"
}
try {
    $settings | ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding utf8
    Invoke-Azure @("webapp", "config", "appsettings", "set", "-g", $state.resourceGroup,
        "-n", $state.appName, "--settings", "@$settingsPath", "-o", "none")
} finally {
    if (Test-Path -LiteralPath $settingsPath) {
        Remove-Item -LiteralPath $settingsPath -Force
    }
}
Write-Output "Configured schema-scoped database credentials, verified TLS, bounded Java heap and same-origin frontend access."
Write-Output "Removed the temporary database bootstrap firewall rule. No credentials were printed."
