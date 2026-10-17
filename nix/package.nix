{
  lib,
  stdenv,
  gradle_9,
  jdk21_headless,
  jdk25_headless,
  makeWrapper,
  # The JVM the server runs on. It doubles as the JDK for datagen and the sidecar bridge (it is
  # found through java.home), and Minecraft 26.x needs Java 25.
  runtimeJdk ? jdk25_headless,
}:

let
  gradle = gradle_9.override { java = jdk25_headless; };
in
stdenv.mkDerivation (finalAttrs: {
  pname = "mcsrc-mcp";
  version = lib.removeSuffix "\n" (
    lib.elemAt (lib.match ".*\nversion=([^\n]*)\n.*" (builtins.readFile ../gradle.properties)) 0
  );

  src = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.unions [
      ../build.gradle.kts
      ../settings.gradle.kts
      ../gradle.properties
      ../LICENSE
      ../NOTICE.md
      ../bridge
      ../cache
      ../core
      ../decompiler
      ../server
    ];
  };

  nativeBuildInputs = [
    gradle
    makeWrapper
  ];

  # Regenerate after changing dependencies: `nix build .#mcsrc-mcp.mitmCache.updateScript && ./result`
  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
  };

  __darwinAllowLocalNetworking = true;

  # The build's Java 21 toolchain comes from the store, so foojay never tries to download one.
  # The configuration cache is off because the dependency fetch can't record under it.
  gradleFlags = [
    "--no-configuration-cache"
    "-Dorg.gradle.java.installations.auto-download=false"
    "-Dorg.gradle.java.installations.paths=${jdk21_headless.home}"
  ];

  gradleBuildTask = ":server:installDist";
  # nixpkgs' default update task doesn't work on Gradle 9; running the real build records the same deps.
  gradleUpdateTask = finalAttrs.gradleBuildTask;

  # Several suites download Minecraft and fork builds, which the sandbox has no network for; CI runs them.
  doCheck = false;

  # installDist's start script already carries the JVM tuning from build.gradle.kts and appends
  # JAVA_OPTS after it, so only the Java it runs on needs setting here.
  installPhase = ''
    runHook preInstall

    mkdir -p $out/share/mcsrc-mcp
    cp -r server/build/install/server/{bin,lib} $out/share/mcsrc-mcp/
    rm $out/share/mcsrc-mcp/bin/server.bat
    makeWrapper $out/share/mcsrc-mcp/bin/server $out/bin/mcsrc-mcp \
      --set JAVA_HOME ${runtimeJdk.home}

    runHook postInstall
  '';

  meta = {
    description = "MCP server to browse Minecraft sources, assets and reports";
    homepage = "https://github.com/cc-bitz/mcsrc-mcp";
    license = lib.licenses.mit;
    mainProgram = "mcsrc-mcp";
    platforms = runtimeJdk.meta.platforms;
    sourceProvenance = with lib.sourceTypes; [
      fromSource
      binaryBytecode # Gradle dependencies
    ];
  };
})
