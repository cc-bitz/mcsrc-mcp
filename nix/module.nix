self:
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.services.mcsrc-mcp;
in
{
  options.services.mcsrc-mcp = {
    enable = lib.mkEnableOption "the mcsrc-mcp MCP server";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${pkgs.stdenv.hostPlatform.system}.default;
      defaultText = lib.literalExpression "mcsrc-mcp.packages.\${pkgs.stdenv.hostPlatform.system}.default";
      description = "The mcsrc-mcp package to run.";
    };

    acceptEula = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = ''
        Whether you accept the Minecraft EULA (https://www.minecraft.net/en-us/eula). Every tool
        that touches Minecraft content refuses to run until this is set.
      '';
    };

    host = lib.mkOption {
      type = lib.types.str;
      default = "127.0.0.1";
      description = ''
        Interface to bind. The server has no authentication and `extract` writes files wherever
        it's told, so don't expose it beyond loopback.
      '';
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 25590;
      description = "Port for both the WebSocket and the Streamable HTTP endpoint (path `/mcp`).";
    };

    user = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      example = "alice";
      description = ''
        User to run as. Left null, the server runs as a sandboxed dynamic user that can write
        only its cache, so `extract` can't reach your projects. Set it to your own user to let
        `extract` write into your home directory.
      '';
    };

    cacheDir = lib.mkOption {
      type = lib.types.str;
      default = "/var/cache/mcsrc-mcp";
      description = ''
        Where downloaded and derived files are cached. The default is managed by systemd; any
        other path must be writable by {option}`services.mcsrc-mcp.user`.
      '';
    };

    idleMinutes = lib.mkOption {
      type = lib.types.ints.unsigned;
      default = 30;
      description = ''
        Minutes without a tool call after which loaded versions are dropped from memory and the
        game helper processes stop, so an idle server shrinks back to a bare JVM. The next call
        reloads from the disk cache. `0` keeps everything loaded.
      '';
    };

    javaOpts = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      example = [ "-Xmx3g" ];
      description = "Extra JVM options, applied after the built-in defaults (so a later `-Xmx` wins).";
    };

    environment = lib.mkOption {
      type = lib.types.attrsOf lib.types.str;
      default = { };
      example = {
        MCSRC_MCP_CACHE_MAX_SIZE_GB = "20";
        MCSRC_MCP_CACHE_TTL_DAYS = "30";
      };
      description = "Extra environment variables for the server.";
    };
  };

  config = lib.mkIf cfg.enable {
    warnings = lib.optional (!cfg.acceptEula) ''
      services.mcsrc-mcp.acceptEula is false, so every tool touching Minecraft content will refuse to run.
    '';

    systemd.services.mcsrc-mcp = {
      description = "mcsrc-mcp MCP server";
      wantedBy = [ "multi-user.target" ];
      wants = [ "network-online.target" ];
      after = [ "network-online.target" ];

      environment = {
        MCSRC_MCP_HOST = cfg.host;
        MCSRC_MCP_PORT = toString cfg.port;
        MCSRC_MCP_CACHE_DIR = cfg.cacheDir;
        MCSRC_MCP_IDLE_MINUTES = toString cfg.idleMinutes;
        JAVA_OPTS = lib.escapeShellArgs cfg.javaOpts;
      }
      // lib.optionalAttrs cfg.acceptEula { MCSRC_MCP_ACCEPT_EULA = "1"; }
      // cfg.environment;

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        Restart = "on-failure";
        RestartSec = 5;
        # The JVM exits 143 on SIGTERM.
        SuccessExitStatus = 143;

        NoNewPrivileges = true;
        PrivateTmp = true;
        PrivateDevices = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectControlGroups = true;
        ProtectClock = true;
        ProtectHostname = true;
        RestrictNamespaces = true;
        RestrictRealtime = true;
        LockPersonality = true;
        RestrictAddressFamilies = [
          "AF_INET"
          "AF_INET6"
          "AF_UNIX"
        ];
      }
      // lib.optionalAttrs (cfg.cacheDir == "/var/cache/mcsrc-mcp") {
        CacheDirectory = "mcsrc-mcp";
      }
      // (
        if cfg.user == null then
          {
            DynamicUser = true;
            ProtectSystem = "strict";
            ProtectHome = true;
          }
        else
          {
            User = cfg.user;
            ProtectSystem = "full";
          }
      );
    };
  };
}
