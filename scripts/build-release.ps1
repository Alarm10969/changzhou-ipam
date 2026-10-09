[CmdletBinding()]
param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $projectRoot 'backend'
$approvedOutput = [IO.Path]::GetFullPath((Join-Path $projectRoot 'release'))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = $approvedOutput }
$requestedOutput = [IO.Path]::GetFullPath($OutputDirectory)
if ($requestedOutput -ne $approvedOutput) { throw "Output directory must be $approvedOutput" }
$OutputDirectory = $requestedOutput
$javaFiles = @(Get-ChildItem (Join-Path $backend 'src\main\java') -Recurse -Filter *.java | ForEach-Object FullName)
$testJavaFiles = @(Get-ChildItem (Join-Path $backend 'src\test\java') -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName)
if ($javaFiles.Count -eq 0) { throw 'No backend Java source files found.' }
$expected = Get-Content (Join-Path $backend 'dependencies.sha256')
foreach ($line in $expected) { if ($line -match '^([0-9A-F]{64})  lib/(.+)$') { $actual=(Get-FileHash (Join-Path $backend ('lib\'+$Matches[2])) -Algorithm SHA256).Hash; if ($actual -ne $Matches[1]) { throw "离线依赖校验和不匹配: $($Matches[2])" } } }
if (Test-Path -LiteralPath $OutputDirectory) { Remove-Item -LiteralPath $OutputDirectory -Recurse -Force }
$classes=Join-Path $OutputDirectory 'classes'; New-Item -ItemType Directory -Force $classes,(Join-Path $OutputDirectory 'lib'),(Join-Path $OutputDirectory 'frontend'),(Join-Path $OutputDirectory 'samples'),(Join-Path $OutputDirectory 'docs') | Out-Null
$classpath = ((Get-ChildItem (Join-Path $backend 'lib') -Filter *.jar | ForEach-Object FullName) -join ';')
& javac --release 11 -encoding UTF-8 -cp $classpath -d $classes $javaFiles
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
if ($testJavaFiles.Count -gt 0) {
  & javac --release 11 -encoding UTF-8 -cp ($classpath + ';' + $classes) -d $classes $testJavaFiles
  if ($LASTEXITCODE -ne 0) { throw 'Java smoke-test compilation failed.' }
}
Copy-Item (Join-Path $backend 'src\main\resources\*') $classes -Recurse -Force
Copy-Item (Join-Path $backend 'lib\*') (Join-Path $OutputDirectory 'lib') -Force
Copy-Item (Join-Path $projectRoot 'frontend\*') (Join-Path $OutputDirectory 'frontend') -Recurse -Force
Copy-Item (Join-Path $projectRoot 'samples\*') (Join-Path $OutputDirectory 'samples') -Recurse -Force
Copy-Item (Join-Path $projectRoot 'deploy') (Join-Path $OutputDirectory 'deploy') -Recurse -Force
Copy-Item (Join-Path $projectRoot 'docs\release\*') (Join-Path $OutputDirectory 'docs') -Force
Copy-Item (Join-Path $projectRoot 'docs\phase-4-mac-address-requirements-2026-09-18.md') (Join-Path $OutputDirectory 'docs') -Force
New-Item -ItemType Directory -Force (Join-Path $OutputDirectory 'docs\acceptance') | Out-Null
Copy-Item (Join-Path $projectRoot 'docs\acceptance\phase-4-v023-mac-independent-acceptance-2026-09-18.md') (Join-Path $OutputDirectory 'docs\acceptance') -Force
Copy-Item (Join-Path $projectRoot 'docs\requirements-v025-2026-10-09.md') (Join-Path $OutputDirectory 'docs') -Force
Copy-Item (Join-Path $projectRoot 'README.md') (Join-Path $OutputDirectory 'README.md') -Force
Copy-Item (Join-Path $projectRoot 'VERSION.txt') (Join-Path $OutputDirectory 'VERSION.txt') -Force
Copy-Item (Join-Path $backend 'dependencies.sha256') (Join-Path $OutputDirectory 'dependencies.sha256') -Force
$manifest=Join-Path $OutputDirectory 'MANIFEST.MF'; "Main-Class: com.icbc.changzhou.ipam.IpamApplication`r`nClass-Path: lib/sqlite-jdbc-3.53.1.0.jar lib/gson-2.10.1.jar lib/jbcrypt-0.4.jar lib/slf4j-api-2.0.17.jar lib/slf4j-nop-2.0.17.jar lib/poi-5.5.1.jar lib/poi-ooxml-5.5.1.jar lib/poi-ooxml-lite-5.5.1.jar lib/xmlbeans-5.3.0.jar lib/commons-codec-1.20.0.jar lib/commons-collections4-4.5.0.jar lib/commons-compress-1.28.0.jar lib/commons-io-2.21.0.jar lib/commons-math3-3.6.1.jar lib/curvesapi-1.08.jar lib/SparseBitSet-1.3.jar lib/log4j-api-2.24.3.jar lib/log4j-to-slf4j-2.24.3.jar`r`n" | Set-Content -LiteralPath $manifest -Encoding ascii
& jar cfm (Join-Path $OutputDirectory 'ipam-stage3.jar') $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' }
Remove-Item -LiteralPath $manifest -Force
Get-FileHash (Join-Path $OutputDirectory 'ipam-stage3.jar') -Algorithm SHA256 | Format-List
Write-Output "Release directory created: $OutputDirectory"
