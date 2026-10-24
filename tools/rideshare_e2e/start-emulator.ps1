param([string]$Label="emulator",[int]$Port=5560,[string]$Avd="Pixel_API35",[string]$Gpu="swiftshader_indirect")
. (Join-Path $PSScriptRoot 'stand-common.ps1')
$p=Get-StandPaths; $sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
$adb=Join-Path $sdk 'platform-tools\adb.exe'
if (& $adb devices | Select-String "emulator-$Port") { throw 'Emulator already attached; existing instance will not be changed' }
$process=Start-StandProcess (Join-Path $sdk 'emulator\emulator.exe') @('-avd',$Avd,'-port',"$Port",'-no-snapshot-save','-no-boot-anim','-no-audio','-gpu',$Gpu) $PSScriptRoot $Label @($Port,($Port+1))
"emulator pid=$($process.Id); root must wait for boot before use"
