{
  description = "ReplayMod 1.19.4 dev environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-23.11";
  inputs.nixpkgs-unstable.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  inputs.flake-utils.url = "github:numtide/flake-utils";

  outputs = { self, nixpkgs, nixpkgs-unstable, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        unstablePkgs = nixpkgs-unstable.legacyPackages.${system};
      in {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            jdk8
            adoptopenjdk-hotspot-bin-16
            jdk17
            jdk21
            unstablePkgs.jdk25
            # Native encoder is built with `zig c++` (windows-gnu target +
            # bundled libc++ + UCRT) instead of nixpkgs' mingw-w64 GCC. The
            # mingw-w64 GCC ships configured with `--enable-threads=mcf`,
            # which leaves the resulting DLL with a runtime dependency on
            # `mcfgthread-12.dll` — a DLL that does NOT ship with Windows,
            # so the cross-built encoder fails to load on user boxes with
            # "Can't find dependent libraries". Zig's bundled toolchain has
            # no such dependency; the produced DLL only needs UCRT
            # (api-ms-win-crt-*, present on Win10/11) and KERNEL32.
            unstablePkgs.zig
            # mingw-w64 toolchain kept around for objdump / nm / inspection;
            # not used as the active compiler.
            pkgsCross.mingwW64.stdenv.cc
            # nv-codec-headers (default attr) in nixpkgs 23.11 is 9.1.23.1, whose
            # NVENCAPI_MAJOR_VERSION = 9 — that disables the entire `#if
            # NVENCAPI_MAJOR_VERSION >= 10` branch in the native encoder and
            # strips the new P1..P7 preset table out of the resulting DLL.
            # Recent NVIDIA drivers / GPUs reject the legacy preset GUIDs, so a
            # DLL built that way fails NvEncInitializeEncoder for every preset.
            # Pin to 12.x so the new presets are always compiled in.
            nv-codec-headers-12
            gradle
            ffmpeg-full
            vulkan-tools
            mesa
            libGL
            libGLU
            pkg-config
            git
          ];

          shellHook = ''
            export JAVA_HOME=${pkgs.jdk21}/lib/openjdk
            export PATH=$JAVA_HOME/bin:$PATH
            export JDK8_HOME=${pkgs.jdk8}/lib/openjdk
            export JDK16_HOME=${pkgs.adoptopenjdk-hotspot-bin-16}
            export JDK17_HOME=${pkgs.jdk17}/lib/openjdk
            export JDK21_HOME=${pkgs.jdk21}/lib/openjdk
            export JDK25_HOME=${unstablePkgs.jdk25}/lib/openjdk
            export FFNV_CODEC_HEADERS=${pkgs.nv-codec-headers-12}/include
            export JAVA_INCLUDE=$JDK21_HOME/include
            export REPLAYMOD_GRADLE_TOOLCHAINS="-Dorg.gradle.java.installations.fromEnv=JDK8_HOME,JDK16_HOME,JDK17_HOME,JDK21_HOME,JDK25_HOME -Dorg.gradle.java.installations.paths=$JDK8_HOME,$JDK16_HOME,$JDK17_HOME,$JDK21_HOME,$JDK25_HOME"
            export GRADLE_OPTS="$REPLAYMOD_GRADLE_TOOLCHAINS ''${GRADLE_OPTS:-}"
            export JAVA_OPTS="$REPLAYMOD_GRADLE_TOOLCHAINS ''${JAVA_OPTS:-}"
            echo "ReplayMod dev shell - Java: $(java -version 2>&1 | head -1)"
            echo "CPUs: $(nproc)  |  DRI: $(ls /dev/dri/ 2>/dev/null | tr '\n' ' ')"
          '';
        };
      });
}
