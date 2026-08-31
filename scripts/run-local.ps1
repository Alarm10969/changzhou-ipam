[CmdletBinding()]
param([int]$Port = 8080, [string]$DataDirectory = (Join-Path $PSScriptRoot '..\backend\data'))
$ErrorActionPreference='Stop'; $root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path; $release=Join-Path $root 'release'
if (!(Test-Path (Join-Path $release 'ipam-stage1.jar'))) { & (Join-Path $PSScriptRoot 'build-release.ps1') }
$env:IPAM_DATA_DIR=$DataDirectory; $env:IPAM_STATIC_DIR=(Join-Path $release 'frontend'); $env:IPAM_PORT=$Port; $env:IPAM_BIND='127.0.0.1'
& java -jar (Join-Path $release 'ipam-stage1.jar')
