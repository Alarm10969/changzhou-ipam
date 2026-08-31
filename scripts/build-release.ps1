[CmdletBinding()]
param([string]$OutputDirectory = (Join-Path $PSScriptRoot '..\release'))
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $projectRoot 'backend'
$approvedOutput = [IO.Path]::GetFullPath((Join-Path $projectRoot 'release'))
$requestedOutput = [IO.Path]::GetFullPath($OutputDirectory)
if ($requestedOutput -ne $approvedOutput) { throw "发布目录只能是项目内的 $approvedOutput" }
$OutputDirectory = $requestedOutput
$javaFiles = @(Get-ChildItem (Join-Path $backend 'src\main\java') -Recurse -Filter *.java | ForEach-Object FullName)
$testJavaFiles = @(Get-ChildItem (Join-Path $backend 'src\test\java') -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName)
if ($javaFiles.Count -eq 0) { throw '未找到后端 Java 源文件。' }
$expected = Get-Content (Join-Path $backend 'dependencies.sha256')
foreach ($line in $expected) { if ($line -match '^([0-9A-F]{64})  lib/(.+)$') { $actual=(Get-FileHash (Join-Path $backend ('lib\'+$Matches[2])) -Algorithm SHA256).Hash; if ($actual -ne $Matches[1]) { throw "离线依赖校验和不匹配: $($Matches[2])" } } }
if (Test-Path -LiteralPath $OutputDirectory) { Remove-Item -LiteralPath $OutputDirectory -Recurse -Force }
$classes=Join-Path $OutputDirectory 'classes'; New-Item -ItemType Directory -Force $classes,(Join-Path $OutputDirectory 'lib'),(Join-Path $OutputDirectory 'frontend') | Out-Null
$classpath = ((Get-ChildItem (Join-Path $backend 'lib') -Filter *.jar | ForEach-Object FullName) -join ';')
& javac --release 11 -encoding UTF-8 -cp $classpath -d $classes $javaFiles
if ($LASTEXITCODE -ne 0) { throw 'Java 编译失败。' }
if ($testJavaFiles.Count -gt 0) {
  & javac --release 11 -encoding UTF-8 -cp ($classpath + ';' + $classes) -d $classes $testJavaFiles
  if ($LASTEXITCODE -ne 0) { throw 'Java 烟测编译失败。' }
}
Copy-Item (Join-Path $backend 'src\main\resources\*') $classes -Recurse -Force
Copy-Item (Join-Path $backend 'lib\*') (Join-Path $OutputDirectory 'lib') -Force
Copy-Item (Join-Path $projectRoot 'frontend\*') (Join-Path $OutputDirectory 'frontend') -Recurse -Force
Copy-Item (Join-Path $projectRoot 'deploy') (Join-Path $OutputDirectory 'deploy') -Recurse -Force
Copy-Item (Join-Path $backend 'dependencies.sha256') (Join-Path $OutputDirectory 'dependencies.sha256') -Force
$manifest=Join-Path $OutputDirectory 'MANIFEST.MF'; "Main-Class: com.icbc.changzhou.ipam.IpamApplication`r`nClass-Path: lib/sqlite-jdbc-3.53.1.0.jar lib/gson-2.10.1.jar lib/jbcrypt-0.4.jar lib/slf4j-api-2.0.17.jar lib/slf4j-nop-2.0.17.jar`r`n" | Set-Content -LiteralPath $manifest -Encoding ascii
& jar cfm (Join-Path $OutputDirectory 'ipam-stage1.jar') $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR 打包失败。' }
Remove-Item -LiteralPath $manifest -Force
Get-FileHash (Join-Path $OutputDirectory 'ipam-stage1.jar') -Algorithm SHA256 | Format-List
Write-Output "已生成可直接复制到麒麟机的第一阶段发布目录: $OutputDirectory"
