[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'build-release.ps1')
if ($LASTEXITCODE -ne 0) { throw '发布构建失败，未生成麒麟交付包。' }
$delivery = Join-Path $projectRoot 'deliverables'
New-Item -ItemType Directory -Force $delivery | Out-Null
$zip = Join-Path $delivery 'ipam-stage1-kylin-arm64.zip'
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path (Join-Path $projectRoot 'release\*') -DestinationPath $zip -Force
Get-FileHash -LiteralPath $zip -Algorithm SHA256 | Format-List
Write-Output "请复制到麒麟机的单一交付包: $zip"
