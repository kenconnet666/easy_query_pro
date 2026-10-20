#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$previous = @{}
$names = @('EQTS_PG_TEST', 'EQTS_PG_PASSWORD', 'EQTS_MYSQL_TEST', 'EQTS_MYSQL_PASSWORD')
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    & wsl.exe -d Debian -- docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The Debian Docker engine is unavailable' }
    foreach ($database in @(@{ Container='eqp-dev-postgres-1'; Password='POSTGRES_PASSWORD'; Prefix='EQTS_PG' },
                            @{ Container='eqp-dev-mysql-1'; Password='MYSQL_PASSWORD'; Prefix='EQTS_MYSQL' })) {
        $raw = & wsl.exe -d Debian -- docker inspect $database.Container --format '{{json .Config.Env}}'
        if ($LASTEXITCODE -ne 0) { throw ('Cannot inspect local test container: ' + $database.Container) }
        $settings = @{}
        foreach ($item in ($raw | ConvertFrom-Json)) {
            $pair = $item.Split('=', 2)
            $settings[$pair[0]] = $pair[1]
        }
        if (-not $settings.ContainsKey($database.Password)) { throw 'Missing local database password' }
        [Environment]::SetEnvironmentVariable($database.Prefix + '_PASSWORD', $settings[$database.Password], 'Process')
        [Environment]::SetEnvironmentVariable($database.Prefix + '_TEST', '1', 'Process')
    }
    & pnpm test:integration
    $testExit = $LASTEXITCODE
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
exit $testExit
