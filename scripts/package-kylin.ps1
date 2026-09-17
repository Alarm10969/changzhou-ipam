[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'build-release.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Release build failed; Kylin package was not created.' }
$classes = Join-Path $projectRoot 'release\classes'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
$manifest = Join-Path $projectRoot 'release\release-files.sha256'
$lines = Get-ChildItem (Join-Path $projectRoot 'release') -Recurse -File | Where-Object { $_.FullName -ne $manifest } | Sort-Object FullName | ForEach-Object {
  $relative = $_.FullName.Substring((Join-Path $projectRoot 'release').Length + 1).Replace('\','/')
  (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative
}
$manifestText = ($lines -join "`n") + "`n"
$asciiWithoutBom = New-Object System.Text.ASCIIEncoding
[IO.File]::WriteAllText($manifest, $manifestText, $asciiWithoutBom)
$manifestBytes = [IO.File]::ReadAllBytes($manifest)
if ($manifestBytes -contains 13) { throw 'Release checksum manifest must use Unix LF line endings.' }
$delivery = Join-Path $projectRoot 'deliverables'
New-Item -ItemType Directory -Force $delivery | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$zip = Join-Path $delivery ("ipam-stage4-v022-manual-assignment-gateway-kylin-arm64-$stamp.zip")
if (Test-Path -LiteralPath $zip) { throw "Delivery already exists: $zip" }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipArchive = [System.IO.Compression.ZipFile]::Open($zip, [System.IO.Compression.ZipArchiveMode]::Create)
try {
  Get-ChildItem (Join-Path $projectRoot 'release') -Recurse -File | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring((Join-Path $projectRoot 'release').Length + 1).Replace('\','/')
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zipArchive, $_.FullName, $relative, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
  }
} finally {
  $zipArchive.Dispose()
}
Get-FileHash -LiteralPath $zip -Algorithm SHA256 | Format-List
Write-Output "New Kylin stage-four V022 manual-assignment-gateway package: $zip"
