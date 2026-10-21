#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start', 'test', 'stop', 'status')]
    [string]$Action = 'start',
    [string]$Distribution = 'Debian',
    [string]$IntegrationTest,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$repoRoot = Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $PSScriptRoot 'EqpCompose.psm1') -Force
$names = @('EQP_HANA_PASSWORD', 'EQP_HANA_JDBC_URL', 'EQP_HANA_USER', 'WSLENV')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $repoRoot
try {
    $linuxRoot = (wsl -d $Distribution --exec wslpath -a $repoRoot.Replace('\', '/')).Trim()
    wsl -d $Distribution --exec docker info --format '{{.ServerVersion}}'
    $container = wsl -d $Distribution --exec docker ps -aq --filter label=com.docker.compose.project=eqp-hana --filter label=com.docker.compose.service=hana
    if ($container) {
        $config = (wsl -d $Distribution --exec docker inspect --format '{{json .Config.Env}}' $container) | ConvertFrom-Json
        $passwordEntry = $config | Where-Object { $_.StartsWith('EQP_HANA_PASSWORD=') }
        if (-not $passwordEntry) { throw 'Existing HANA container does not contain the expected bootstrap environment.' }
        $env:EQP_HANA_PASSWORD = $passwordEntry.Substring('EQP_HANA_PASSWORD='.Length)
    } elseif ($Action -ne 'start') {
        throw 'HANA is not deployed. Run tools/hana.ps1 -Action start first.'
    } elseif (-not $env:EQP_HANA_PASSWORD) {
        $volumes = wsl -d $Distribution --exec docker volume ls -q --filter label=com.docker.compose.project=eqp-hana
        if ($volumes) { throw 'HANA data already exists. Supply its original password through EQP_HANA_PASSWORD.' }
        $env:EQP_HANA_PASSWORD = 'E9a' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    }
    $env:WSLENV = (@($env:WSLENV, 'EQP_HANA_PASSWORD') | Where-Object { $_ }) -join ':'
    $compose = @('-d', $Distribution, '--cd', $linuxRoot, '--exec', 'docker', 'compose',
        '-f', 'infra/hana.compose.yaml', '-p', 'eqp-hana')
    switch ($Action) {
        'start' {
            & wsl @compose up --detach --wait --wait-timeout 900
            $memory = Assert-ComposeMemoryLimit -Compose $compose -Distribution $Distribution -Service hana
            $container = (& wsl @compose ps -q hana).Trim()
            wsl -d $Distribution --exec docker exec $container /bin/bash /eqp/configure.sh
            Write-Host "HANA HXE started on localhost:39041; SYSTEMDB uses localhost:39017. Memory limit: $($memory / 1GB) GiB, no additional swap."
        }
        'test' {
            $ports = (wsl -d $Distribution --exec docker inspect --format '{{json .NetworkSettings.Ports}}' $container) | ConvertFrom-Json
            $port = $ports.'39041/tcp'[0].HostPort
            if (-not $port) { throw 'The HXE tenant port is not published. Run tools/hana.ps1 -Action start to apply the Compose configuration.' }
            $env:EQP_HANA_JDBC_URL = "jdbc:sap://127.0.0.1:$port"
            $env:EQP_HANA_USER = 'SYSTEM'
            $mavenArgs = @('--batch-mode', 'verify', '-Phana')
            if ($Clean) { $mavenArgs = @('--batch-mode', 'clean', 'verify', '-Phana') }
            if ($IntegrationTest) { $mavenArgs += "-Dit.test=$IntegrationTest" }
            & .\mvnw.cmd @mavenArgs
        }
        'stop' { & wsl @compose stop }
        'status' { & wsl @compose ps }
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
