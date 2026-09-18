param(
    [switch]$Probe,
    [string]$OutputDirectory = "",
    [string]$DeviceId = "",
    [string]$DeviceName = "",
    [ValidateSet("AUTO", "FEEDER", "FLATBED")]
    [string]$Source = "AUTO",
    [string]$Duplex = "false",
    [int]$Dpi = 300,
    [int]$MaxPages = 250
)

$ErrorActionPreference = "Stop"
$ScannerDeviceType = 1
$WiaFormatBmp = "{B96B3CAB-0728-11D3-9D7B-0000F81EF32E}"
$DocumentHandlingStatus = 3087
$DocumentHandlingSelect = 3088
$Pages = 3096
$HorizontalResolution = 6147
$VerticalResolution = 6148
$Feeder = 1
$Flatbed = 2
$DuplexFlag = 4
$FeedReady = 1
$DuplexEnabled = $Duplex.Equals("true", [System.StringComparison]::OrdinalIgnoreCase) -or $Duplex -eq "1"

function Get-ScannerInfos {
    $manager = New-Object -ComObject WIA.DeviceManager
    return @($manager.DeviceInfos | Where-Object { [int]$_.Type -eq $ScannerDeviceType })
}

function Get-InfoValue {
    param($Info, [int]$PropertyId)
    if ($null -eq $Info) { return $null }
    try {
        foreach ($property in @($Info.Properties)) {
            if ([int]$property.PropertyID -eq $PropertyId) { return $property.Value }
        }
    } catch { }
    return $null
}

function Find-WiaProperty {
    param($Object, [int]$PropertyId)
    if ($null -eq $Object) { return $null }
    try {
        foreach ($property in @($Object.Properties)) {
            if ([int]$property.PropertyID -eq $PropertyId) { return $property }
        }
    } catch { }
    return $null
}

function Get-WiaPropertyValue {
    param($Primary, $Secondary, [int]$PropertyId)
    $property = Find-WiaProperty $Primary $PropertyId
    if ($null -eq $property) { $property = Find-WiaProperty $Secondary $PropertyId }
    if ($null -eq $property) { return $null }
    try { return $property.Value } catch { return $null }
}

function Set-WiaPropertyIfWritable {
    param($Primary, $Secondary, [int]$PropertyId, $Value)
    $property = Find-WiaProperty $Primary $PropertyId
    if ($null -eq $property) { $property = Find-WiaProperty $Secondary $PropertyId }
    if ($null -eq $property) { return $false }
    try {
        if (-not $property.IsReadOnly) {
            $property.Value = $Value
            return $true
        }
    } catch { }
    return $false
}

function Select-ScannerDevice {
    param([object[]]$ScannerInfos)

    if (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
        $match = $ScannerInfos | Where-Object { ([string](Get-InfoValue $_ 2)) -eq $DeviceId } | Select-Object -First 1
        if ($null -eq $match) { throw "LEXAVAULT_WIA_DEVICE_NOT_FOUND" }
        return $match.Connect()
    }

    if (-not [string]::IsNullOrWhiteSpace($DeviceName)) {
        $match = $ScannerInfos | Where-Object { ([string](Get-InfoValue $_ 7)).Equals($DeviceName, [System.StringComparison]::OrdinalIgnoreCase) } | Select-Object -First 1
        if ($null -eq $match) { throw "LEXAVAULT_WIA_DEVICE_NOT_FOUND" }
        return $match.Connect()
    }

    if ($ScannerInfos.Count -eq 1) { return $ScannerInfos[0].Connect() }

    # More than one scanner: let the signed-in workstation operator choose explicitly.
    $dialog = New-Object -ComObject WIA.CommonDialog
    $selected = $dialog.ShowSelectDevice($ScannerDeviceType, $false, $false)
    if ($null -eq $selected) { throw "LEXAVAULT_WIA_CANCELLED" }
    return $selected
}

try {
    $scanners = @(Get-ScannerInfos)
    if ($Probe) {
        if ($scanners.Count -lt 1) { exit 20 }
        if (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
            $match = $scanners | Where-Object { ([string](Get-InfoValue $_ 2)) -eq $DeviceId } | Select-Object -First 1
            if ($null -eq $match) { exit 21 }
        }
        if (-not [string]::IsNullOrWhiteSpace($DeviceName)) {
            $match = $scanners | Where-Object { ([string](Get-InfoValue $_ 7)).Equals($DeviceName, [System.StringComparison]::OrdinalIgnoreCase) } | Select-Object -First 1
            if ($null -eq $match) { exit 21 }
        }
        Write-Output "SCANNER_COUNT=$($scanners.Count)"
        exit 0
    }

    if ($scanners.Count -lt 1) { throw "LEXAVAULT_WIA_NO_SCANNER" }
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { throw "LEXAVAULT_WIA_OUTPUT_REQUIRED" }
    if ($MaxPages -lt 1 -or $MaxPages -gt 1000) { throw "LEXAVAULT_WIA_MAX_PAGES_INVALID" }
    if ($Dpi -lt 75 -or $Dpi -gt 1200) { throw "LEXAVAULT_WIA_DPI_INVALID" }

    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    if ((Get-ChildItem -LiteralPath $OutputDirectory -Force | Measure-Object).Count -ne 0) {
        throw "LEXAVAULT_WIA_OUTPUT_NOT_EMPTY"
    }

    $device = Select-ScannerDevice $scanners
    if ($null -eq $device -or $device.Items.Count -lt 1) { throw "LEXAVAULT_WIA_DEVICE_NO_ITEMS" }
    $items = @($device.Items)
    $item = $items[0]

    $effectiveSource = $Source
    if ($Source -eq "AUTO" -or $Source -eq "FEEDER") {
        # WIA 2.0 commonly exposes feeder and flatbed as separate child items. Prefer an item
        # whose feeder status says paper is ready instead of blindly using Item(1).
        foreach ($candidate in $items) {
            $candidateStatus = Get-WiaPropertyValue $candidate $device $DocumentHandlingStatus
            if ($null -ne $candidateStatus -and (([int]$candidateStatus -band $FeedReady) -ne 0)) {
                $item = $candidate
                $effectiveSource = "FEEDER"
                break
            }
        }
        if ($Source -eq "FEEDER" -and $effectiveSource -ne "FEEDER") {
            foreach ($candidate in $items) {
                if ($null -ne (Find-WiaProperty $candidate $DocumentHandlingSelect)) {
                    $item = $candidate
                    $effectiveSource = "FEEDER"
                    break
                }
            }
        }
    }
    if ($Source -eq "AUTO" -and $effectiveSource -eq "AUTO") { $effectiveSource = "FLATBED" }

    if ($effectiveSource -eq "FEEDER") {
        $handling = $Feeder
        if ($DuplexEnabled) { $handling = $handling -bor $DuplexFlag }
        [void](Set-WiaPropertyIfWritable $item $device $DocumentHandlingSelect $handling)
        # WIA defines zero as ALL_PAGES. We still enforce MaxPages in this process.
        [void](Set-WiaPropertyIfWritable $item $device $Pages 0)
    }
    elseif ($effectiveSource -eq "FLATBED") {
        [void](Set-WiaPropertyIfWritable $item $device $DocumentHandlingSelect $Flatbed)
    }

    [void](Set-WiaPropertyIfWritable $item $device $HorizontalResolution $Dpi)
    [void](Set-WiaPropertyIfWritable $item $device $VerticalResolution $Dpi)

    $page = 0
    while ($page -lt $MaxPages) {
        if ($effectiveSource -eq "FEEDER" -and $page -gt 0) {
            $status = Get-WiaPropertyValue $item $device $DocumentHandlingStatus
            if ($null -ne $status -and (([int]$status -band $FeedReady) -eq 0)) { break }
        }

        try {
            $image = $item.Transfer($WiaFormatBmp)
        }
        catch {
            $hresultHex = ("0x{0:X8}" -f ($_.Exception.HResult -band 0xFFFFFFFFL))
            if ($effectiveSource -eq "FEEDER" -and $page -gt 0 -and $hresultHex -eq "0x80210003") { break }
            throw
        }

        if ($null -eq $image) { throw "LEXAVAULT_WIA_EMPTY_TRANSFER" }
        $page++
        $pagePath = Join-Path $OutputDirectory ("page-{0:D4}.bmp" -f $page)
        $image.SaveFile($pagePath)
        if (-not (Test-Path -LiteralPath $pagePath) -or (Get-Item -LiteralPath $pagePath).Length -lt 1) {
            throw "LEXAVAULT_WIA_EMPTY_PAGE"
        }

        if ($effectiveSource -eq "FLATBED") { break }
    }

    if ($page -lt 1) { throw "LEXAVAULT_WIA_NO_PAGES" }
    if ($page -ge $MaxPages -and $effectiveSource -eq "FEEDER") {
        $status = Get-WiaPropertyValue $item $device $DocumentHandlingStatus
        if ($null -eq $status -or (([int]$status -band $FeedReady) -ne 0)) {
            throw "LEXAVAULT_WIA_MAX_PAGES_REACHED"
        }
    }

    Write-Output "PAGE_COUNT=$page"
    exit 0
}
catch {
    $message = [string]$_.Exception.Message
    if ($message -match "LEXAVAULT_WIA_CANCELLED") { Write-Error "LEXAVAULT_WIA_CANCELLED"; exit 23 }
    if ($message -match "LEXAVAULT_WIA_NO_SCANNER") { Write-Error "LEXAVAULT_WIA_NO_SCANNER"; exit 20 }
    if ($message -match "LEXAVAULT_WIA_DEVICE_NOT_FOUND") { Write-Error "LEXAVAULT_WIA_DEVICE_NOT_FOUND"; exit 21 }
    if ($message -match "LEXAVAULT_WIA_MAX_PAGES_REACHED") { Write-Error "LEXAVAULT_WIA_MAX_PAGES_REACHED"; exit 25 }
    Write-Error ("LEXAVAULT_WIA_ACQUISITION_FAILED:{0}" -f $_.Exception.GetType().Name)
    exit 30
}
