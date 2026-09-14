# mcsrc-mcp

MCP server to browse Minecraft sources/assets/reports.

## 100% Ai generated

This was a project I had thrown together by a couple of agents because I got bored of LLMs constantly asking for decompiled sources or checking compiled class files with `javap` when talking about Minecraft internals.

## Build

Requires JDK 21 or newer.

```bash
./gradlew :server:installDist
```

Point any MCP client that launches a subprocess at `server/build/install/server/bin/server`
(`server.bat` on Windows) and set `MCSRC_MCP_ACCEPT_EULA=1` in its environment — accepting the
Minecraft EULA is required before any tool that touches Minecraft content will run.

## Cache

Downloaded and derived files are cached under `%LOCALAPPDATA%\mcsrc-mcp\cache` (Windows),
`~/Library/Caches/mcsrc-mcp` (macOS), or `$XDG_CACHE_HOME/mcsrc-mcp` (Linux, falls back to
`~/.cache/mcsrc-mcp`). Set `MCSRC_MCP_CACHE_DIR` to use a different location.
