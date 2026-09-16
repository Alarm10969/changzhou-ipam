$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'test-stage3.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Stages 1–8 regression failed.' }
$release=Join-Path $root 'release'
$cp=((Get-ChildItem (Join-Path $release 'lib') -Filter *.jar | ForEach-Object FullName) + (Join-Path $release 'classes')) -join ';'
& java -cp $cp com.icbc.changzhou.ipam.StageNinePhaseFourSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Phase four backend regression failed.' }
& node (Join-Path $root 'scripts\browser-phase4-regression.js')
if ($LASTEXITCODE -ne 0) { throw 'Phase four browser regression failed.' }
& node --check (Join-Path $root 'frontend\app.js')
if ($LASTEXITCODE -ne 0) { throw 'Frontend syntax check failed.' }
git -C $root diff --check
if ($LASTEXITCODE -ne 0) { throw 'Diff whitespace check failed.' }
Write-Output 'PASS|STAGE4_FULL_REGRESSION|stages-1-to-8|backend-phase4|browser-phase4|node-check|diff-check'
