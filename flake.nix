{
  description = "MCP server to browse Minecraft sources, assets and reports";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = forAllSystems (pkgs: rec {
        mcsrc-mcp = pkgs.callPackage ./nix/package.nix { };
        default = mcsrc-mcp;
      });

      # So `nix flake check` builds the package, which is what catches a stale nix/deps.json.
      checks = forAllSystems (pkgs: {
        inherit (self.packages.${pkgs.stdenv.hostPlatform.system}) mcsrc-mcp;
      });

      overlays.default = final: _prev: {
        mcsrc-mcp = final.callPackage ./nix/package.nix { };
      };

      nixosModules = rec {
        mcsrc-mcp = import ./nix/module.nix self;
        default = mcsrc-mcp;
      };

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.jdk21 ];
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt);
    };
}
