# Boots the module in a VM and speaks MCP to the real service over both transports, so a flake check
# covers the server as it actually runs: the systemd sandbox, the environment the module sets, and a
# Ktor upgrade's effect on the wire rather than on Ktor's test engine.
self: {
  name = "mcsrc-mcp";

  nodes.machine =
    { pkgs, ... }:
    {
      imports = [ self.nixosModules.default ];

      services.mcsrc-mcp = {
        enable = true;
        acceptEula = true;
      };

      environment.systemPackages = [
        pkgs.curl
        pkgs.websocat
      ];
    };

  testScript = ''
    import json
    import shlex

    URL = "http://127.0.0.1:25590/mcp"
    INITIALIZE = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "vm-test", "version": "0"},
        },
    }

    def post(body, session=None):
        cmd = [
            "curl", "-sf", "-D", "/tmp/headers", "-X", "POST", URL,
            "-H", "content-type: application/json",
            "-H", "accept: application/json, text/event-stream",
        ]
        if session:
            cmd += ["-H", f"mcp-session-id: {session}", "-H", "mcp-protocol-version: 2025-06-18"]
        cmd += ["-d", json.dumps(body)]
        return machine.succeed(" ".join(shlex.quote(c) for c in cmd))

    machine.wait_for_unit("mcsrc-mcp.service")
    machine.wait_for_open_port(25590)

    with subtest("runs sandboxed, caching where the module says"):
        machine.succeed("systemctl show -p DynamicUser mcsrc-mcp | grep -q yes")
        machine.succeed("test -d /var/cache/mcsrc-mcp")

    with subtest("Streamable HTTP: initialize, then list tools in the session"):
        init = json.loads(post(INITIALIZE))
        assert init["result"]["serverInfo"]["name"] == "mcsrc-mcp", init
        session = machine.succeed(
            "awk -F': ' 'tolower($1) == \"mcp-session-id\" { print $2 }' /tmp/headers | tr -d '\\r'"
        ).strip()
        assert session, "initialize assigned no session id"
        post({"jsonrpc": "2.0", "method": "notifications/initialized"}, session)
        tools = json.loads(post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, session))
        names = [tool["name"] for tool in tools["result"]["tools"]]
        assert "get_class_source" in names, names

    with subtest("Streamable HTTP: the GET stream opens and stays open"):
        # curl gives up after 3s; 28 is its timeout code, which here means the stream was still open.
        # (The driver runs commands under set -e, hence the ||.)
        machine.succeed(
            f"curl -s -N -m 3 -D /tmp/stream -H 'accept: text/event-stream' -H 'mcp-session-id: {session}'"
            f" -H 'mcp-protocol-version: 2025-06-18' {URL} > /tmp/events || test $? -eq 28"
        )
        machine.succeed("grep -qi '^content-type: text/event-stream' /tmp/stream")

    with subtest("WebSocket: initialize"):
        reply = machine.succeed(
            # Input held open a moment: at EOF websocat closes, which would drop the reply in flight.
            f"(echo {shlex.quote(json.dumps(INITIALIZE))}; sleep 2)"
            " | timeout 20 websocat --text --buffer-size 1048576 --protocol mcp ws://127.0.0.1:25590/mcp | head -n 1"
        )
        assert json.loads(reply)["result"]["serverInfo"]["name"] == "mcsrc-mcp", reply
  '';
}
