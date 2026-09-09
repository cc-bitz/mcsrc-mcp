@echo off
setlocal

rem stdout is the MCP protocol channel, so every diagnostic in this script is sent to stderr
rem (>&2). Anything echoed to stdout would be read by the client as a malformed message.

set "JAVA_EXE=java.exe"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

if defined JAVA_HOME (
    if not exist "%JAVA_EXE%" (
        echo mcsrc-mcp: JAVA_HOME is "%JAVA_HOME%" but there is no java.exe under its bin. >&2
        exit /b 1
    )
) else (
    where java.exe >nul 2>&1
    if errorlevel 1 (
        echo mcsrc-mcp: no Java on PATH. Install JDK 21 or newer, or set JAVA_HOME to one. >&2
        exit /b 1
    )
)

rem The same JVM tuning the Gradle start scripts apply. Without the ceiling the JVM takes a
rem quarter of physical RAM as its max heap and never hands it back, and the periodic GC is what
rem actually uncommits memory while the server sits idle between tool calls.
"%JAVA_EXE%" -Xmx1500m -XX:+UseG1GC -XX:G1PeriodicGCInterval=60000 -XX:G1PeriodicGCSystemLoadThreshold=0 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 -jar "%~dp0mcsrc-mcp.jar" %*
