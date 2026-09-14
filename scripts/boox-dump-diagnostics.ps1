[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [switch]$GrantDump,

    [switch]$GrantUsageStats,

    [switch]$RawRecents
)

$ErrorActionPreference = 'Stop'

function Invoke-AdbCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$Quiet
    )

    $output = & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed ($LASTEXITCODE): adb -s $Serial $($Arguments -join ' ')"
    }

    if (-not $Quiet) {
        return $output
    }
}

Write-Host "Checking BOOX cover sync package on serial $Serial"
$packagePath = & adb -s $Serial shell pm path tw.mustp.booxcoversync 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($packagePath -join ''))) {
    Write-Warning 'tw.mustp.booxcoversync is not installed on this device.'
    exit 1
}
Write-Host 'Package is installed.'

if ($GrantDump) {
    Write-Host 'Attempting android.permission.DUMP grant (signature protection may reject this).'
    try {
        Invoke-AdbCommand -Arguments @('shell', 'pm', 'grant', 'tw.mustp.booxcoversync', 'android.permission.DUMP') -Quiet
        Write-Host 'DUMP grant command accepted.'
    } catch {
        Write-Warning $_.Exception.Message
        Write-Warning 'A rejected grant is expected on builds that protect DUMP as a signature permission.'
    }
}

if ($GrantUsageStats) {
    Write-Host 'Attempting GET_USAGE_STATS app-op grant.'
    try {
        Invoke-AdbCommand -Arguments @('shell', 'appops', 'set', 'tw.mustp.booxcoversync', 'android:get_usage_stats', 'allow') -Quiet
        Write-Host 'GET_USAGE_STATS app-op grant command accepted.'
    } catch {
        Write-Warning $_.Exception.Message
        Write-Warning 'The app-op may be controlled by device policy; enable Usage Stats access in Settings if needed.'
    }
}

$usageStats = & adb -s $Serial shell appops get tw.mustp.booxcoversync android:get_usage_stats 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Warning 'Unable to query GET_USAGE_STATS app-op.'
} elseif (($usageStats -join ' ') -match 'allow') {
    Write-Host 'GET_USAGE_STATS app-op: allowed.'
} else {
    Write-Host 'GET_USAGE_STATS app-op: not allowed.'
}

$recents = & adb -s $Serial shell dumpsys activity recents 2>&1
$recentsExitCode = $LASTEXITCODE
$recentsText = $recents -join [Environment]::NewLine
if ($recentsExitCode -ne 0) {
    if ($recentsText -match 'PACKAGE_USAGE_STATS') {
        Write-Warning 'dumpsys activity recents was denied: PACKAGE_USAGE_STATS is required on this firmware.'
    } else {
        Write-Warning 'Unable to read dumpsys activity recents.'
    }
    exit 0
}

if ($RawRecents) {
    Write-Host 'Raw recents output is intentionally suppressed to avoid exposing URI or file path data.'
}

$matches = [regex]::Matches(
    $recentsText,
    'intent=\{.*(android\.intent\.action\.VIEW|com\.onyx\.kreader).*\}',
    [System.Text.RegularExpressions.RegexOptions]::Singleline
)
if ($matches.Count -eq 0) {
    Write-Host 'No NeoReader ACTION_VIEW intent found. Open an EPUB in NeoReader and retry.'
    exit 0
}

Write-Host "NeoReader ACTION_VIEW record found ($($matches.Count)); content URI and file path withheld."
