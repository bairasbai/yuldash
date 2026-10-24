param([string]$Label="pwa-preview")
. (Join-Path $PSScriptRoot 'stand-common.ps1')
$p=Get-StandPaths; $web=Join-Path $p.Repo 'webapp'; $vite=Join-Path $web 'node_modules\vite\bin\vite.js'
$process=Start-StandProcess 'node' @($vite,'preview','--host','127.0.0.1','--port','4173','--strictPort') $web $Label @(4173)
"preview pid=$($process.Id)"
