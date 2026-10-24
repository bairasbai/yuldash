$ErrorActionPreference = 'Stop'
function Get-StandPaths {
  if (-not $env:RSACC_SCRATCH) { throw 'RSACC_SCRATCH is required (task-owned temporary directory)' }
  $repoPath = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
  $scratchPath = [IO.Path]::GetFullPath($env:RSACC_SCRATCH)
  $evidencePath = if ($env:RSACC_EVID) { [IO.Path]::GetFullPath($env:RSACC_EVID) } else { Join-Path $repoPath 'test-results\release' }
  $owner = if ($env:RSACC_OWNER) { $env:RSACC_OWNER } else { 'RIDESHARE-CLAUDE-FINISH-20261024' }
  [IO.Directory]::CreateDirectory($scratchPath) | Out-Null
  [IO.Directory]::CreateDirectory((Join-Path $evidencePath 'logs')) | Out-Null
  $marker = Join-Path $scratchPath 'owner.txt'
  if ((Test-Path -LiteralPath $marker) -and ([IO.File]::ReadAllText($marker) -ne $owner)) { throw 'Scratch owner mismatch' }
  [IO.File]::WriteAllText($marker, $owner)
  return @{ Repo=$repoPath; Scratch=$scratchPath; Evidence=$evidencePath; Owner=$owner }
}
function Start-StandProcess([string]$Executable, [string[]]$Arguments, [string]$Cwd, [string]$Label, [int[]]$Ports) {
  $paths = Get-StandPaths
  if ($Label -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid process label' }
  foreach ($port in $Ports) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is occupied; no existing process will be touched" }
  }
  $quoted = @($Arguments | ForEach-Object { if ($_ -match '["\r\n]') { throw 'Unsupported process argument' }; '"' + $_ + '"' })
  $process = Start-Process -FilePath $Executable -ArgumentList ($quoted -join ' ') -WorkingDirectory $Cwd -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $paths.Evidence "logs\$Label.log") -RedirectStandardError (Join-Path $paths.Evidence "logs\$Label.err.log")
  Start-Sleep -Milliseconds 500
  $actual = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
  if (-not $actual) { throw "Process $Label exited during startup; inspect its log" }
  $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)" | ForEach-Object { @{pid=$_.ProcessId;created=$_.CreationDate.ToString('o');cmd=$_.CommandLine} })
  $record = @{owner=$paths.Owner;label=$Label;pid=$actual.ProcessId;created=$actual.CreationDate.ToString('o');cmd=$actual.CommandLine;cwd=$Cwd;ports=$Ports;children=$children;started=(Get-Date).ToString('o')}
  ($record | ConvertTo-Json -Compress -Depth 6) | Add-Content -LiteralPath (Join-Path $paths.Evidence 'processes.jsonl') -Encoding UTF8
  return $process
}
