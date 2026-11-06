param([string]$Serial = "emulator-5560")
# Build the QA-only Unicode text helper (classic UiAutomator jar) from uitext/src and push it to the emulator.
# Needs the SDK's android-35 platform (android.jar, uiautomator.jar, optional/android.test.base.jar) and build-tools 35.
$ErrorActionPreference = 'Stop'
$H = Join-Path $PSScriptRoot 'uitext'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
Remove-Item -Recurse -Force "$H\classes", "$H\out" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$H\classes", "$H\out" | Out-Null
$p = "$sdk\platforms\android-35"
$cp = "$p\android.jar;$p\uiautomator.jar;$p\optional\android.test.base.jar"
$srcs = Get-ChildItem -Recurse "$H\src" -Filter *.java | ForEach-Object FullName
& "$jbr\bin\javac.exe" --release 8 -nowarn -Xlint:-options -cp $cp -d "$H\classes" @srcs
if ($LASTEXITCODE) { throw "javac $LASTEXITCODE" }
$cls = Get-ChildItem -Recurse "$H\classes" -Filter *.class | ForEach-Object FullName
$env:JAVA_HOME = $jbr
& "$sdk\build-tools\35.0.0\d8.bat" --min-api 26 --lib "$p\android.jar" --classpath "$p\uiautomator.jar" --classpath "$p\optional\android.test.base.jar" --output "$H\out\qa-uitext.jar" @cls
if ($LASTEXITCODE) { throw "d8 $LASTEXITCODE" }
& "$sdk\platform-tools\adb.exe" -s $Serial push "$H\out\qa-uitext.jar" /data/local/tmp/qa-uitext.jar
"qa-uitext.jar sha256=" + (Get-FileHash "$H\out\qa-uitext.jar").Hash
