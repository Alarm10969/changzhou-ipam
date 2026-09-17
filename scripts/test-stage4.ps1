$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'test-stage3.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Stages 1–8 regression failed.' }
$release=Join-Path $root 'release'
$cp=((Get-ChildItem (Join-Path $release 'lib') -Filter *.jar | ForEach-Object FullName) + (Join-Path $release 'classes')) -join ';'
& java -cp $cp com.icbc.changzhou.ipam.StageNinePhaseFourSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Phase four backend regression failed.' }
& java -cp $cp com.icbc.changzhou.ipam.StageTenPlanningOverlapSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Six-network overlap regression failed.' }
& java -cp $cp com.icbc.changzhou.ipam.StageElevenManualAssignmentGatewaySmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Manual assignment and gateway regression failed.' }
$legacyV019=Join-Path $root '.local-v020-acceptance\ipam.db'
if (-not (Test-Path -LiteralPath $legacyV019)) { throw 'V019 historical acceptance database is missing.' }
& java -cp $cp com.icbc.changzhou.ipam.StageTwelveV019UpgradeSmokeTest $legacyV019
if ($LASTEXITCODE -ne 0) { throw 'V019 historical database upgrade regression failed.' }
& node (Join-Path $root 'scripts\browser-phase4-regression.js')
if ($LASTEXITCODE -ne 0) { throw 'Phase four browser regression failed.' }
& node --check (Join-Path $root 'frontend\app.js')
if ($LASTEXITCODE -ne 0) { throw 'Frontend syntax check failed.' }
git -C $root diff --check
if ($LASTEXITCODE -ne 0) { throw 'Diff whitespace check failed.' }
Write-Output 'PASS|STAGE4_FULL_REGRESSION|stages-1-to-8|backend-phase4|planning-overlap|manual-gateway|v019-history-upgrade|browser-phase4|node-check|diff-check'
