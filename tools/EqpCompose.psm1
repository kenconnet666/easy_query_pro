#Requires -Version 7.0

# Docker silently drops limits it cannot enforce, so compare the running container with the Compose declaration.
function Assert-ComposeMemoryLimit {
    param(
        [Parameter(Mandatory)][string[]]$Compose,
        [Parameter(Mandatory)][string]$Distribution,
        [Parameter(Mandatory)][string]$Service
    )
    $declared = [long]((& wsl @Compose config --format json | ConvertFrom-Json).services.$Service.mem_limit)
    $container = (& wsl @Compose ps -q $Service).Trim()
    $limits = (wsl -d $Distribution --exec docker inspect --format '{{json .HostConfig}}' $container) | ConvertFrom-Json
    if ($limits.Memory -ne $declared -or $limits.MemorySwap -ne $declared) {
        throw "$Service runs with memory=$($limits.Memory) memory+swap=$($limits.MemorySwap); Compose declares $declared bytes without swap."
    }
    return $declared
}

Export-ModuleMember -Function Assert-ComposeMemoryLimit
