#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('mysql', 'postgres', 'hana')]
    [string]$Database,
    [string]$Distribution = 'Debian'
)

$ErrorActionPreference = 'Stop'
$project = if ($Database -eq 'hana') { 'eqp-hana' } else { 'eqp-dev' }
$key = switch ($Database) {
    'mysql' { 'MYSQL_PASSWORD=' }
    'postgres' { 'POSTGRES_PASSWORD=' }
    'hana' { 'EQP_HANA_PASSWORD=' }
}
$container = wsl -d $Distribution --exec docker ps -aq --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=$Database"
if ($LASTEXITCODE -ne 0) { throw 'Docker container lookup failed.' }
if (-not $container) { throw "No $Database development container exists for project $project." }
$containerEnvironment = wsl -d $Distribution --exec docker inspect --format '{{json .Config.Env}}' $container
if ($LASTEXITCODE -ne 0) { throw 'Docker container inspection failed.' }
$entries = $containerEnvironment | ConvertFrom-Json
$entry = $entries | Where-Object { $_.StartsWith($key) }
if (-not $entry) { throw 'The development credential is missing from the container environment.' }
Set-Clipboard -Value $entry.Substring($key.Length)
Write-Host "$Database password copied to the clipboard without printing it."
