param([string]$Label="proxy")
. (Join-Path $PSScriptRoot 'stand-common.ps1')
$p=Get-StandPaths; $env:WIRE_LOG=Join-Path $p.Evidence ("logs\$Label-wire.jsonl")
$process=Start-StandProcess 'node' @((Join-Path $PSScriptRoot 'proxy.mjs')) $PSScriptRoot $Label @(8000,8099)
"proxy pid=$($process.Id)"
