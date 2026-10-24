param([Parameter(Mandatory=$true)][string]$ProfileDir,[string]$Phase='started')
. (Join-Path $PSScriptRoot 'stand-common.ps1')
$paths = Get-StandPaths
$profilePath = [IO.Path]::GetFullPath($ProfileDir)
$all = @(Get-CimInstance Win32_Process)
$owned = @($all | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($profilePath) -and $_.Name -match '^(chrome|chromium|headless_shell)\.exe$' })
$known = @{}
foreach ($item in $owned) { $known[$item.ProcessId] = $item }
do {
  $added = $false
  foreach ($item in $all) {
    if ($known.ContainsKey($item.ParentProcessId) -and -not $known.ContainsKey($item.ProcessId)) { $known[$item.ProcessId] = $item; $added = $true }
  }
} while ($added)
foreach ($item in $known.Values) {
  @{owner=$paths.Owner;label='browser-'+$Phase;pid=$item.ProcessId;created=$item.CreationDate.ToString('o');cmd=$item.CommandLine;cwd=$paths.Repo;ports=@();profile=$profilePath;observed=(Get-Date).ToString('o')} | ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $paths.Evidence 'processes.jsonl') -Encoding UTF8
}
"browser phase=$Phase recorded=$($known.Count)"
