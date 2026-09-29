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

The server speaks MCP over WebSocket, so it runs on its own and every client connects to the same
process — one JVM and one set of warm, decompiled versions, however many agents are using it.

```bash
MCSRC_MCP_ACCEPT_EULA=1 server/build/install/server/bin/server   # server.bat on Windows
```

Accepting the Minecraft EULA is required before any tool that touches Minecraft content will run,
and has to be set in the server's environment, not the client's.

It listens on `ws://127.0.0.1:25590/mcp`. Set `MCSRC_MCP_PORT` to change the port, and
`MCSRC_MCP_HOST` to bind another interface — but there is no authentication, and `extract` writes
files wherever it's told, so don't expose it beyond loopback. Handshakes from browser pages on
other sites are refused.

Then point the client at it. For Claude Code:

```json
{
  "mcpServers": {
    "mcsrc-mcp": { "type": "ws", "url": "ws://127.0.0.1:25590/mcp" }
  }
}
```

## Cache

Downloaded and derived files are cached under `%LOCALAPPDATA%\mcsrc-mcp\cache` (Windows),
`~/Library/Caches/mcsrc-mcp` (macOS), or `$XDG_CACHE_HOME/mcsrc-mcp` (Linux, falls back to
`~/.cache/mcsrc-mcp`). Set `MCSRC_MCP_CACHE_DIR` to use a different location.
