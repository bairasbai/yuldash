param([string]$Label = "emulator", [int]$Port = 5560, [string]$Gpu = "swiftshader_indirect")
# Boot the shared Pixel_API35 AVD on its own console port without saving the snapshot back.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path
$evid = Join-Path $repo 'test-results\release'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
if (& $adb devices | Select-String "emulator-$Port") { throw "emulator-$Port already attached" }
$p = Start-Process -FilePath "$sdk\emulator\emulator.exe" -ArgumentList @('-avd','Pixel_API35','-port',"$Port",'-no-snapshot-save','-no-boot-anim','-no-audio','-gpu',$Gpu) `
  -RedirectStandardOutput (Join-Path $evid "logs\$Label.log") -RedirectStandardError (Join-Path $evid "logs\$Label.err.log") -WindowStyle Hidden -PassThru
$proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
$rec = [ordered]@{ owner='claude/rideshare-acceptance-main-20261024'; kind='emulator'; label=$Label; pid=$p.Id;
  created=$proc.CreationDate.ToString('o'); cmd=$proc.CommandLine; ports=@($Port, $Port+1); serial="emulator-$Port"; started=(Get-Date).ToString('o') }
($rec | ConvertTo-Json -Compress -Depth 5) | Add-Content -Encoding utf8 (Join-Path $evid 'processes.jsonl')
& $adb -s "emulator-$Port" wait-for-device
for ($i=0; $i -lt 180; $i++) { $b = (& $adb -s "emulator-$Port" shell getprop sys.boot_completed 2>$null); if ($b -match '1') { break }; Start-Sleep 1 }
"emulator pid=$($p.Id) boot_completed=$b"
