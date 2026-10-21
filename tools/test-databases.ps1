#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('mysql', 'postgres', 'all')]
    [string]$Database = 'all',
    [string]$Distribution = 'Debian'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$repoRoot = Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $PSScriptRoot 'EqpCompose.psm1') -Force
$project = 'eqp-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$services = if ($Database -eq 'all') { @('mysql', 'postgres') } else { @($Database) }
$environmentNames = @('EQP_DB_PASSWORD', 'EQP_MYSQL_PORT', 'EQP_PG_PORT', 'WSLENV',
    'EQP_MYSQL_JDBC_URL', 'EQP_MYSQL_USER', 'EQP_MYSQL_PASSWORD',
    'EQP_PG_JDBC_URL', 'EQP_PG_USER', 'EQP_PG_PASSWORD')
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$compose = @()
$started = $false
Push-Location $repoRoot
try {
    $linuxRoot = (wsl -d $Distribution --exec wslpath -a $repoRoot.Replace('\', '/')).Trim()
    wsl -d $Distribution --exec docker info --format '{{.ServerVersion}}'
    $env:EQP_DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    # Use distinct ephemeral ports without changing existing development services.
    $listeners = @()
    try {
        foreach ($name in @('EQP_MYSQL_PORT', 'EQP_PG_PORT')) {
            $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
            $listener.Start()
            $listeners += $listener
            [Environment]::SetEnvironmentVariable($name, [string]$listener.LocalEndpoint.Port, 'Process')
        }
    } finally {
        foreach ($listener in $listeners) { $listener.Stop() }
    }
    $env:WSLENV = (@($env:WSLENV, 'EQP_DB_PASSWORD', 'EQP_MYSQL_PORT', 'EQP_PG_PORT') |
            Where-Object { $_ }) -join ':'
    $compose = @('-d', $Distribution, '--cd', $linuxRoot, '--exec', 'docker', 'compose',
        '-f', 'infra/compose.yaml', '-p', $project)
    foreach ($service in $services) { $compose += @('--profile', $service) }
    $started = $true
    & wsl @compose up --detach --wait --wait-timeout 120
    foreach ($service in $services) {
        $memory = Assert-ComposeMemoryLimit -Compose $compose -Distribution $Distribution -Service $service
        Write-Host "$service memory=memory+swap=$memory bytes"
    }
    $env:EQP_MYSQL_JDBC_URL = "jdbc:mysql://127.0.0.1:$env:EQP_MYSQL_PORT/eqp?sslMode=DISABLED&allowPublicKeyRetrieval=true"
    $env:EQP_MYSQL_USER = 'eqp'
    $env:EQP_MYSQL_PASSWORD = $env:EQP_DB_PASSWORD
    $env:EQP_PG_JDBC_URL = "jdbc:postgresql://127.0.0.1:$env:EQP_PG_PORT/eqp"
    $env:EQP_PG_USER = 'eqp'
    $env:EQP_PG_PASSWORD = $env:EQP_DB_PASSWORD
    # Isolated runs use a clean build; Failsafe profiles select the database tests explicitly.
    & .\mvnw.cmd --batch-mode clean verify "-P$($services -join ',')"
} finally {
    try {
        if ($started) { & wsl @compose down --volumes --remove-orphans }
    } finally {
        foreach ($name in $environmentNames) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
        Pop-Location
    }
}
