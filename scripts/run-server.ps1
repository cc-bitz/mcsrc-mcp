<#
.SYNOPSIS
Keeps the mcsrc-mcp server running. This is what the logon task from autostart.ps1 executes.

.DESCRIPTION
Runs the server from a copy of the dist/ build, not dist/ itself: the JVM holds its jar open, and
Windows won't let `gradlew :server:dist` replace an open file. The copy is refreshed every time the
server starts, so `autostart.ps1 restart` is how a new build goes live.

When the server exits, for any reason, it's started again. When something else already holds the
port (a copy started by hand), this waits instead, since starting a second would only fail to bind.
#>
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [int]$Port = 25590,
    [switch]$AcceptEula
)

$ErrorActionPreference = 'Stop'

$dist = Join-Path (Split-Path $PSScriptRoot -Parent) 'dist'
$runDir = Join-Path $env:LOCALAPPDATA 'mcsrc-mcp\run'
$log = Join-Path $runDir 'server.log'

New-Item -ItemType Directory -Force $runDir | Out-Null
$env:MCSRC_MCP_PORT = "$Port"
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if ($AcceptEula) { $env:MCSRC_MCP_ACCEPT_EULA = '1' }

while ($true) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        Start-Sleep -Seconds 30
        continue
    }

    if (Test-Path $log) { Move-Item $log "$log.old" -Force }
    if (Test-Path (Join-Path $dist 'mcsrc-mcp.jar')) {
        Copy-Item (Join-Path $dist 'mcsrc-mcp.jar'), (Join-Path $dist 'mcsrc-mcp.bat') $runDir -Force
    } elseif (-not (Test-Path (Join-Path $runDir 'mcsrc-mcp.jar'))) {
        Set-Content $log "mcsrc-mcp: no build to run - run ``gradlew :server:dist`` in $(Split-Path $dist -Parent)"
        Start-Sleep -Seconds 60
        continue
    }

    # The launcher carries the JVM flags, so they stay defined in one place.
    Start-Process cmd.exe -ArgumentList '/c', "`"$(Join-Path $runDir 'mcsrc-mcp.bat')`"" `
        -NoNewWindow -Wait -RedirectStandardError $log -RedirectStandardOutput "$log.stdout"
    # Paced, so a server that dies on startup doesn't spin.
    Start-Sleep -Seconds 5
}
