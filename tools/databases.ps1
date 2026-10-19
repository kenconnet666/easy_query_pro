#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action = 'start',
    [string]$Distribution = 'Debian'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$repoRoot = Split-Path $PSScriptRoot -Parent
$names = @('EQP_DB_PASSWORD', 'WSLENV')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $repoRoot
try {
    $linuxRoot = (wsl -d $Distribution --exec wslpath -a $repoRoot.Replace('\', '/')).Trim()
    wsl -d $Distribution --exec docker info --format '{{.ServerVersion}}'
    $savedPassword = $null
    foreach ($service in @('mysql', 'postgres')) {
        $container = wsl -d $Distribution --exec docker ps -aq --filter label=com.docker.compose.project=eqp-dev --filter "label=com.docker.compose.service=$service"
        if (-not $container) { continue }
        $entries = (wsl -d $Distribution --exec docker inspect --format '{{json .Config.Env}}' $container) | ConvertFrom-Json
        $key = if ($service -eq 'mysql') { 'MYSQL_PASSWORD=' } else { 'POSTGRES_PASSWORD=' }
        $entry = $entries | Where-Object { $_.StartsWith($key) }
        if (-not $entry) { throw "Missing bootstrap credential in the existing $service container." }
        $password = $entry.Substring($key.Length)
        if ($savedPassword -and $savedPassword -cne $password) { throw 'Development container credentials do not agree; refusing to replace them.' }
        $savedPassword = $password
    }
    if ($savedPassword) {
        $env:EQP_DB_PASSWORD = $savedPassword
    } elseif (-not $env:EQP_DB_PASSWORD) {
        $volumes = wsl -d $Distribution --exec docker volume ls -q --filter label=com.docker.compose.project=eqp-dev
        if ($volumes) { throw 'Existing development data requires its original EQP_DB_PASSWORD.' }
        if ($Action -ne 'start') { throw 'Development databases are not deployed. Run tools/databases.ps1 -Action start.' }
        $env:EQP_DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    }
    $env:WSLENV = (@($env:WSLENV, 'EQP_DB_PASSWORD') | Where-Object { $_ }) -join ':'
    $compose = @('-d', $Distribution, '--cd', $linuxRoot, '--exec', 'docker', 'compose',
        '-f', 'infra/compose.yaml', '-p', 'eqp-dev', '--profile', 'mysql', '--profile', 'postgres')
    switch ($Action) {
        'start' {
            & wsl @compose up --detach --wait --wait-timeout 180
            foreach ($service in @('mysql', 'postgres')) {
                $container = (& wsl @compose ps -q $service).Trim()
                $limits = (wsl -d $Distribution --exec docker inspect --format '{{json .HostConfig}}' $container) | ConvertFrom-Json
                $expected = if ($service -eq 'mysql') { 2GB } else { 1GB }
                if ($limits.Memory -ne $expected -or $limits.MemorySwap -ne $expected) { throw "Unexpected memory limits for $service." }
            }
            Write-Host 'Development databases ready: MySQL localhost:53306 (2 GiB), PostgreSQL localhost:55432 (1 GiB).'
            Write-Host 'Database/user: eqp. Use tools/database-password.ps1 to copy a credential without printing it.'
        }
        'stop' { & wsl @compose stop }
        'status' { & wsl @compose ps }
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
