{
  description = "Jolt-based OpenAI-compatible LLM proxy";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    jolt = {
      # Jolt's flake includes its submodules; use the git scheme so Nix
      # preserves that requirement when it locks this input.
      url = "git+https://github.com/jolt-lang/jolt.git?submodules=1";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      jolt,
    }:
    let
      # Match the platforms currently supported by Jolt's upstream flake.
      systems = [
        "x86_64-linux"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      mkProxy = pkgs:
        let
          joltPackage = jolt.packages.${pkgs.stdenv.hostPlatform.system}.default;
        in
        pkgs.writeShellApplication {
          name = "jolt-llm-proxy";
          runtimeInputs = [ joltPackage ];
          text = ''
            # The project's pinned http-client/jolt-crypto revisions load
            # OpenSSL through the platform loader rather than Jolt's newer
            # JOLT_OPENSSL_LIBDIR seam. Keep both paths available.
            export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath [ pkgs.openssl ]}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
            export DYLD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath [ pkgs.openssl ]}''${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"

            # Run from the immutable flake source so `deps.edn`, src/, and
            # resources/ are available even when invoked outside the checkout.
            cd ${self}
            exec jolt -m llm-proxy.core "$@"
          '';
          meta = {
            description = "Run the jolt-llm-proxy CLI";
            homepage = "https://github.com/jasalt/jolt-llm-proxy";
            mainProgram = "jolt-llm-proxy";
          };
        };
    in
    {
      packages = forAllSystems (pkgs: {
        default = mkProxy pkgs;
      });

      apps = forAllSystems (pkgs: {
        default = {
          type = "app";
          program = "${self.packages.${pkgs.stdenv.hostPlatform.system}.default}/bin/jolt-llm-proxy";
          meta.description = "Run jolt-llm-proxy";
        };
      });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [
            jolt.packages.${pkgs.stdenv.hostPlatform.system}.default
            pkgs.git
            pkgs.unzip
            pkgs.openssl
          ];

          # Jolt's HTTP/TLS code loads OpenSSL dynamically. These match the
          # environment seams configured by the current upstream Jolt flake.
          JOLT_OPENSSL_LIBDIR = pkgs.lib.makeLibraryPath [ pkgs.openssl ];
          LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [ pkgs.openssl ];
          DYLD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [ pkgs.openssl ];
          SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";

          shellHook = ''
            echo "jolt-llm-proxy development environment"
            printf 'Jolt: '
            jolt --version
          '';
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt);
    };
}
