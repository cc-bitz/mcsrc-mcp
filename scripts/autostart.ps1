<#
.SYNOPSIS
Starts the mcsrc-mcp server at logon on Windows, hidden, and keeps it running.

.DESCRIPTION
  autostart.ps1 install [-JavaHome <jdk>] [-Port <n>] [-AcceptEula]
      Registers the logon task and starts it now. -AcceptEula sets MCSRC_MCP_ACCEPT_EULA=1 for the
      server; read https://www.minecraft.net/en-us/eula first.
  autostart.ps1 restart
      Stops the running server. The task starts it again within seconds, from a fresh copy of dist/,
      so this is how a new `gradlew :server:dist` build goes live.
  autostart.ps1 uninstall
      Stops the server and removes the task.

The server's log is %LOCALAPPDATA%\mcsrc-mcp\run\server.log.
#>
param(
    [ValidateSet('install', 'restart', 'uninstall')]
    [string]$Action = 'install',
    [string]$JavaHome = $env:JAVA_HOME,
    [int]$Port = 25590,
    [switch]$AcceptEula
)

$ErrorActionPreference = 'Stop'
$TaskName = 'mcsrc-mcp'

function Stop-Server {
    param([int]$Port)

    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { Stop-Process -Id $listener.OwningProcess -Force }
}

# The port the installed task runs on, which isn't necessarily the -Port default.
function Get-TaskPort {
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($task -and $task.Actions[0].Arguments -match '-Port (\d+)') { [int]$Matches[1] } else { $Port }
}

switch ($Action) {
    'install' {
        $runner = Join-Path $PSScriptRoot 'run-server.ps1'
        $runnerArgs = "-NoProfile -ExecutionPolicy Bypass -File `"$runner`" -Port $Port"
        if ($JavaHome) { $runnerArgs += " -JavaHome `"$JavaHome`"" }
        if ($AcceptEula) { $runnerArgs += ' -AcceptEula' }

        # conhost --headless gives the console processes a console that is never shown. A plain
        # powershell -WindowStyle Hidden still flashes a window at every logon.
        $taskAction = New-ScheduledTaskAction -Execute 'conhost.exe' -Argument "--headless powershell.exe $runnerArgs"
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
        # A zero time limit means none. The default of three days would kill the server mid-week.
        $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew `
            -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable

        Register-ScheduledTask -TaskName $TaskName -Action $taskAction -Trigger $trigger -Settings $settings `
            -Description 'Keeps the mcsrc-mcp MCP server running (scripts/autostart.ps1).' -Force | Out-Null
        Start-ScheduledTask -TaskName $TaskName
        "Installed. mcsrc-mcp will listen on ws://127.0.0.1:$Port/mcp and http://127.0.0.1:$Port/mcp - log: $env:LOCALAPPDATA\mcsrc-mcp\run\server.log"
    }

    'restart' {
        Stop-Server (Get-TaskPort)
        'Stopped; the task restarts it from a fresh copy of dist/ within a few seconds.'
    }

    'uninstall' {
        $port = Get-TaskPort
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
        # The loop before the server, or it would start the server again the moment it's stopped.
        # Stop-ScheduledTask isn't enough: it ends conhost, and the powershell under it keeps going.
        Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
            Where-Object { $_.CommandLine -like '*run-server.ps1*' } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
        Stop-Server $port
        'Uninstalled.'
    }
}
