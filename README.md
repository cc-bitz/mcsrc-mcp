# mcsrc-mcp

MCP server to browse Minecraft sources/assets/reports.

## 100% Ai generated

This was a project I had thrown together by a couple of agents because I got bored of LLMs constantly asking for decompiled sources or checking compiled class files with `javap` when talking about Minecraft internals.

## Build

Requires JDK 21 or newer.

```bash
./gradlew :server:installDist
```

## Run

The server speaks MCP over WebSocket and Streamable HTTP, so it runs on its own and every client
connects to the same process — one JVM and one set of warm, decompiled versions, however many
agents are using it.

```bash
MCSRC_MCP_ACCEPT_EULA=1 server/build/install/server/bin/server   # server.bat on Windows
```

Accepting the Minecraft EULA is required before any tool that touches Minecraft content will run,
and has to be set in the server's environment, not the client's.

It listens on `ws://127.0.0.1:25590/mcp` and `http://127.0.0.1:25590/mcp` — one port and path
for both, so use whichever your client speaks. Set `MCSRC_MCP_PORT` to change the port, and
`MCSRC_MCP_HOST` to bind another interface — but there is no authentication, and `extract` writes
files wherever it's told, so don't expose it beyond loopback. Requests from browser pages on
other sites are refused.

On Windows, `scripts/autostart.ps1` starts it hidden at every logon and restarts it if it exits.
It runs a copy of the `dist/` build, so rebuilding never hits a locked jar:

```powershell
./gradlew :server:dist
powershell -ExecutionPolicy Bypass -File scripts/autostart.ps1 install -AcceptEula   # -JavaHome <jdk>, -Port <n>
powershell -ExecutionPolicy Bypass -File scripts/autostart.ps1 restart               # after a rebuild
powershell -ExecutionPolicy Bypass -File scripts/autostart.ps1 uninstall
```

Then point the client at it, over whichever transport it supports. For Claude Code, WebSocket:

```json
{
  "mcpServers": {
    "mcsrc-mcp": { "type": "ws", "url": "ws://127.0.0.1:25590/mcp" }
  }
}
```

or Streamable HTTP, which most other MCP clients speak:

```json
{
  "mcpServers": {
    "mcsrc-mcp": { "type": "http", "url": "http://127.0.0.1:25590/mcp" }
  }
}
```

Both reach the same server, so they behave identically. An HTTP session left idle for a day is
closed; the client's next request gets a 404 and it initializes again.

## Paper, Folia and Purpur

For Minecraft 26.x, fork variants serve the fork's real source — vanilla with the fork's patches
applied, `// Paper start` comments and Javadoc included, plus its own classes — rather than a
decompile of its jar. It is rebuilt the way paperweight's userdev builds it: codebook's unpick, a
whole-jar Vineflower decompile with mache's settings, mache's patches, then the dev bundle's. The
first use of a Minecraft version takes about a minute in the background (reads get decompiles
meanwhile); every fork and build of that version shares the result. `find_declaration` and
`get_method_source` resolve the fork's lines from its own bytecode, whose line numbers match this
source. Older versions — obfuscated mache, or no mache — keep serving decompiled bytecode.

## Cache

Downloaded and derived files are cached under `%LOCALAPPDATA%\mcsrc-mcp\cache` (Windows),
`~/Library/Caches/mcsrc-mcp` (macOS), or `$XDG_CACHE_HOME/mcsrc-mcp` (Linux, falls back to
`~/.cache/mcsrc-mcp`). Set `MCSRC_MCP_CACHE_DIR` to use a different location.
