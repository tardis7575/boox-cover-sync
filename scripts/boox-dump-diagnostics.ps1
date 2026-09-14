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
$packageInstalled =
    $LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($packagePath -join ''))
Write-Host "Package installed: $packageInstalled"
if (-not $packageInstalled) {
    Write-Warning 'tw.mustp.booxcoversync is not installed on this device.'
    exit 1
}

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
    Write-Host 'Attempting android.permission.PACKAGE_USAGE_STATS grant.'
    try {
        Invoke-AdbCommand -Arguments @('shell', 'pm', 'grant', 'tw.mustp.booxcoversync', 'android.permission.PACKAGE_USAGE_STATS') -Quiet
        Write-Host 'PACKAGE_USAGE_STATS manifest permission grant command accepted.'
    } catch {
        Write-Warning $_.Exception.Message
        Write-Warning 'The manifest permission may be controlled by device policy; the app-op alone is not sufficient.'
    }

    Write-Host 'Attempting GET_USAGE_STATS app-op grant.'
    try {
        Invoke-AdbCommand -Arguments @('shell', 'appops', 'set', 'tw.mustp.booxcoversync', 'android:get_usage_stats', 'allow') -Quiet
        Write-Host 'GET_USAGE_STATS app-op grant command accepted.'
    } catch {
        Write-Warning $_.Exception.Message
        Write-Warning 'The app-op may be controlled by device policy; enable Usage Stats access in Settings if needed.'
    }
}

$usagePermission = & adb -s $Serial shell pm check-permission android.permission.PACKAGE_USAGE_STATS tw.mustp.booxcoversync 2>&1
$usagePermissionExitCode = $LASTEXITCODE
$usagePermissionText = $usagePermission -join ' '
$manifestUsageStatsGranted =
    $usagePermissionExitCode -eq 0 -and $usagePermissionText -match '(?i)\bgranted\b'
Write-Host "PACKAGE_USAGE_STATS manifest permission granted: $manifestUsageStatsGranted"

$usageStats = & adb -s $Serial shell appops get tw.mustp.booxcoversync android:get_usage_stats 2>&1
$usageStatsAllowed = $false
if ($LASTEXITCODE -ne 0) {
    Write-Warning 'Unable to query GET_USAGE_STATS app-op.'
} else {
    $usageStatsAllowed = ($usageStats -join ' ') -match '(?i)\ballow(?:ed)?\b'
    Write-Host "GET_USAGE_STATS app-op allowed: $usageStatsAllowed"
}

$usageStatsReady = $manifestUsageStatsGranted -and $usageStatsAllowed
Write-Host "Usage Stats requirements complete: $usageStatsReady"

if ($RawRecents) {
    Write-Host 'Raw recents output suppressed: true'
}

$recents = & adb -s $Serial shell dumpsys activity recents 2>&1
$recentsExitCode = $LASTEXITCODE
$recentsText = $recents -join [Environment]::NewLine
$recentsReadable = $recentsExitCode -eq 0
Write-Host "dumpsys activity recents readable: $recentsReadable"
if (-not $recentsReadable) {
    if ($recentsText -match 'PACKAGE_USAGE_STATS') {
        Write-Warning 'dumpsys activity recents was denied; both PACKAGE_USAGE_STATS permission and GET_USAGE_STATS app-op are required on this firmware.'
    } else {
        Write-Warning 'Unable to read dumpsys activity recents.'
    }
    Write-Host 'NeoReader ACTION_VIEW record found: false'
    exit 0
}

$matches = [regex]::Matches(
    $recentsText,
    'intent=\{.*(android\.intent\.action\.VIEW|com\.onyx\.kreader).*\}',
    [System.Text.RegularExpressions.RegexOptions]::Singleline
)
$neoReaderViewFound = $matches.Count -gt 0
Write-Host "NeoReader ACTION_VIEW record found: $neoReaderViewFound"
if (-not $neoReaderViewFound) {
    Write-Host 'Open an EPUB in NeoReader and retry.'
} else {
    Write-Host 'NeoReader ACTION_VIEW record details withheld: true'
}
