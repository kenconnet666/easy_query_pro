#requires -Version 7.0
param([ValidateSet('mysql','postgres','hana')][string[]]$Database = @('mysql','postgres','hana'))
$ErrorActionPreference = 'Stop'
$previous = @{}
$names = @('EQTS_PG_TEST', 'EQTS_PG_PASSWORD', 'EQTS_MYSQL_TEST', 'EQTS_MYSQL_PASSWORD', 'EQTS_HANA_TEST', 'EQTS_HANA_PASSWORD')
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($prefix in @('EQTS_PG', 'EQTS_MYSQL', 'EQTS_HANA')) { [Environment]::SetEnvironmentVariable($prefix + '_TEST', '0', 'Process') }
    & wsl.exe -d Debian -- docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The Debian Docker engine is unavailable' }
    $targets = @(@{ Name='postgres'; Container='eqp-dev-postgres-1'; Password='POSTGRES_PASSWORD'; Prefix='EQTS_PG' },
                 @{ Name='mysql'; Container='eqp-dev-mysql-1'; Password='MYSQL_PASSWORD'; Prefix='EQTS_MYSQL' },
                 @{ Name='hana'; Container='eqp-hana-hana-1'; Password='EQP_HANA_PASSWORD'; Prefix='EQTS_HANA' })
    foreach ($target in ($targets | Where-Object { $_.Name -in $Database })) {
        $raw = & wsl.exe -d Debian -- docker inspect $target.Container --format '{{json .Config.Env}}'
        if ($LASTEXITCODE -ne 0) { throw ('Cannot inspect local test container: ' + $target.Container) }
        $settings = @{}
        foreach ($item in ($raw | ConvertFrom-Json)) {
            $pair = $item.Split('=', 2)
            $settings[$pair[0]] = $pair[1]
        }
        if (-not $settings.ContainsKey($target.Password)) { throw 'Missing local database password' }
        [Environment]::SetEnvironmentVariable($target.Prefix + '_PASSWORD', $settings[$target.Password], 'Process')
        [Environment]::SetEnvironmentVariable($target.Prefix + '_TEST', '1', 'Process')
    }
    & pnpm test:integration
    $testExit = $LASTEXITCODE
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
exit $testExit
