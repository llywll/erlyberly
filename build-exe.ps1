# 打包 erlyberly 为自包含单文件 exe（内嵌 JRE + JavaFX，目标机无需安装 Java）
# 用法（PowerShell）: .\build-exe.ps1
# 用法（CMD）:        powershell -ExecutionPolicy Bypass -File build-exe.ps1
$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

# 外部命令（java/mvn/jpackage/7za）会把正常日志写到 stderr；
# 在 ErrorActionPreference=Stop 下 PowerShell 5.1 会把它误判为致命错误。
# 这里临时切到 Continue 执行，仅用退出码判断成败。
function Invoke-Native {
    param([Parameter(Mandatory)][scriptblock]$Cmd, [string]$ErrMsg = '外部命令执行失败')
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Cmd } finally { $ErrorActionPreference = $old }
    if ($LASTEXITCODE -ne 0) { throw "$ErrMsg (exit=$LASTEXITCODE)" }
}

# 1) 定位 JDK 26（需带 jpackage/jlink）与 erlc
$JDK    = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Users\admin\.jdks\openjdk-26.0.1' }
$ErlBin = 'D:\Program Files\erl-23.3\bin'
$env:JAVA_HOME = $JDK
$env:PATH      = "$JDK\bin;$ErlBin;$env:PATH"

$java     = Join-Path $JDK 'bin\java.exe'
$jpackage = Join-Path $JDK 'bin\jpackage.exe'
foreach ($exe in @($java, $jpackage)) {
    if (-not (Test-Path $exe)) { throw "未找到: $exe（请检查 JDK 路径，或先设置 JAVA_HOME 环境变量）" }
}
$ErrorActionPreference = 'Continue'
$jdkVer = (& $java -version 2>&1 | Select-Object -First 1)
$ErrorActionPreference = 'Stop'
Write-Host ">> JDK: $jdkVer"

$Version = '0.7.0'
$Jar     = "erlyberly-$Version-runnable.jar"
$root    = (Get-Location).Path

# 2) 构建 fat jar（maven-wrapper -> Maven 3.3.3，Main-Class=erlyberly.Launcher）
Write-Host '>> mvn package ...'
Invoke-Native {
    & $java -classpath '.mvn\wrapper\maven-wrapper.jar' "-Dmaven.multiModuleProjectDirectory=$root" `
        org.apache.maven.wrapper.MavenWrapperMain -DskipTests clean package
} 'Maven 构建失败'

# 3) 准备干净的输入目录，避免把整个 target 拷进 app-image
Remove-Item -Recurse -Force target\dist, target\jpackage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path target\dist | Out-Null
Copy-Item "target\$Jar" target\dist\

# 4) jpackage 生成 app-image（自包含 exe + 精简 runtime）
Write-Host '>> jpackage ...'
$addModules = 'java.base,java.datatransfer,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.sql,java.xml,jdk.jfr,jdk.unsupported,jdk.unsupported.desktop,jdk.crypto.ec,jdk.crypto.cryptoki'
Invoke-Native {
    & $jpackage `
        --type app-image `
        --name erlyberly `
        --app-version $Version `
        --input target\dist `
        --main-jar $Jar `
        --main-class erlyberly.Launcher `
        --dest target\jpackage `
        --java-options '-Dfile.encoding=UTF-8' `
        --java-options '--enable-native-access=ALL-UNNAMED' `
        --add-modules $addModules `
        --vendor 'erlyberly'
} 'jpackage 失败'
Write-Host '>> 完成 app-image: target\jpackage\erlyberly\erlyberly.exe'

# 5) 打包为单文件自解压 exe（7z SFX）
Write-Host '>> 打包单文件 exe (7z SFX) ...'
$sfx     = 'lib\7z.sfx'
$sevenza = 'tools\extra\x64\7za.exe'
if (-not (Test-Path $sevenza)) { $sevenza = 'tools\extra\7za.exe' }

$archive = 'target\jpackage\erlyberly.7z'
Remove-Item -Force $archive -ErrorAction SilentlyContinue
# 在 jpackage 目录内压缩，使压缩包内顶层为 erlyberly\，SFX 解压后即 erlyberly\erlyberly.exe
Push-Location target\jpackage
try {
    Invoke-Native { & "$root\$sevenza" a -t7z -mx=9 -mmt=on erlyberly.7z erlyberly | Out-Null } '7z 压缩失败'
} finally { Pop-Location }

# SFX 运行配置：解压到临时目录后启动 erlyberly.exe（GUIMode=2 静默解压）
# 必须写成 UTF-8 无 BOM，否则 7z SFX 解析头标记会失败
$cfg     = 'target\jpackage\sfx-config.txt'
$cfgText = ";!@Install@!UTF-8!`r`nTitle=`"erlyberly`"`r`nGUIMode=`"2`"`r`nRunProgram=`"erlyberly\\erlyberly.exe`"`r`n"
[System.IO.File]::WriteAllText((Join-Path $root $cfg), $cfgText, (New-Object System.Text.UTF8Encoding($false)))

# 二进制拼接：SFX 头 + 配置 + 压缩包 = 单文件 exe（不能用文本拼接，会破坏二进制）
$out     = "target\jpackage\erlyberly-$Version-setup.exe"
$outPath = Join-Path $root $out
$fs = [System.IO.File]::Create($outPath)
try {
    foreach ($part in @($sfx, $cfg, $archive)) {
        $bytes = [System.IO.File]::ReadAllBytes((Join-Path $root $part))
        $fs.Write($bytes, 0, $bytes.Length)
    }
} finally { $fs.Close() }

$mb = [math]::Round((Get-Item $outPath).Length / 1MB, 1)
Write-Host ">> 完成单文件 exe: $out ($mb MB)"