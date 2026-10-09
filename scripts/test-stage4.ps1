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
if (Test-Path -LiteralPath $legacyV019) {
  & java -cp $cp com.icbc.changzhou.ipam.StageTwelveV019UpgradeSmokeTest $legacyV019
} else {
  & java -cp $cp com.icbc.changzhou.ipam.StageTwelveV019UpgradeSmokeTest
}
if ($LASTEXITCODE -ne 0) { throw 'V019 historical database upgrade regression failed.' }
if (Test-Path -LiteralPath $legacyV019) {
  & java -cp $cp com.icbc.changzhou.ipam.StageThirteenMacAddressSmokeTest $legacyV019
} else {
  & java -cp $cp com.icbc.changzhou.ipam.StageThirteenMacAddressSmokeTest
}
if ($LASTEXITCODE -ne 0) { throw 'MAC address regression failed.' }
& node (Join-Path $root 'scripts\browser-phase4-regression.js')
if ($LASTEXITCODE -ne 0) { throw 'Phase four browser regression failed.' }
& node (Join-Path $root 'scripts\browser-v025-regression.js')
if ($LASTEXITCODE -ne 0) { throw 'V025 browser regression failed.' }
& node --check (Join-Path $root 'frontend\app.js')
if ($LASTEXITCODE -ne 0) { throw 'Frontend syntax check failed.' }
git -C $root diff --check
if ($LASTEXITCODE -ne 0) { throw 'Diff whitespace check failed.' }

& java -cp $cp com.icbc.changzhou.ipam.StageFourteenResourceMaintenanceSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'V025 resource maintenance smoke test failed.' }
& java -cp $cp com.icbc.changzhou.ipam.StageFifteenBackupRestoreSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'V025 backup and restore smoke test failed.' }
Write-Output 'PASS|STAGE4_FULL_REGRESSION|stages-1-to-15|resource-maintenance|backup-restore|browser-phase4|browser-v025|node-check|diff-check'
