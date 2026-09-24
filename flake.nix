{
  description = "Track how fast online appointments at public offices get booked";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in {
      packages = forAll (pkgs: {
        default = pkgs.python3Packages.buildPythonApplication {
          pname = "visitorstracker";
          version = "0.1.0";
          pyproject = true;
          src = ./.;
          build-system = [ pkgs.python3Packages.hatchling ];
          dependencies = [ pkgs.python3Packages.requests ];
          pythonImportsCheck = [ "visitorstracker" ];
        };
      });

      nixosModules.default = { config, lib, pkgs, ... }:
        let
          cfg = config.services.visitorstracker;
          package = self.packages.${pkgs.stdenv.hostPlatform.system}.default;
          db = "/var/lib/visitorstracker/slots.db";
          args = "--db ${db}" + lib.optionalString (cfg.offices != null) " --offices ${cfg.offices}";
          hardening = {
            User = "visitorstracker";
            Group = "visitorstracker";
            StateDirectory = "visitorstracker";
            ProtectSystem = "strict";
            ProtectHome = true;
            PrivateTmp = true;
            PrivateDevices = true;
            NoNewPrivileges = true;
            ProtectKernelTunables = true;
            ProtectKernelModules = true;
            ProtectControlGroups = true;
            RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_UNIX" ];
            RestrictNamespaces = true;
            LockPersonality = true;
            MemoryDenyWriteExecute = true;
            SystemCallArchitectures = "native";
          };
        in {
          options.services.visitorstracker = {
            enable = lib.mkEnableOption "appointment availability tracking";
            interval = lib.mkOption {
              type = lib.types.str;
              default = "10min";
              description = "Time between polling rounds (systemd time span).";
            };
            host = lib.mkOption {
              type = lib.types.str;
              default = "127.0.0.1";
              description = "Address the dashboard listens on.";
            };
            port = lib.mkOption {
              type = lib.types.port;
              default = 8093;
              description = "Port the dashboard listens on.";
            };
            offices = lib.mkOption {
              type = lib.types.nullOr lib.types.path;
              default = null;
              description = "offices.toml replacing the bundled list.";
            };
          };

          config = lib.mkIf cfg.enable {
            users.users.visitorstracker = { isSystemUser = true; group = "visitorstracker"; };
            users.groups.visitorstracker = { };

            systemd.services.visitorstracker-collect = {
              description = "Poll appointment systems once";
              after = [ "network-online.target" ];
              wants = [ "network-online.target" ];
              serviceConfig = hardening // {
                Type = "oneshot";
                ExecStart = "${package}/bin/visitorstracker ${args} collect";
              };
            };
            systemd.timers.visitorstracker-collect = {
              wantedBy = [ "timers.target" ];
              timerConfig = {
                OnBootSec = "2min";
                OnUnitInactiveSec = cfg.interval;
              };
            };

            systemd.services.visitorstracker-web = {
              description = "Appointment availability dashboard";
              wantedBy = [ "multi-user.target" ];
              after = [ "network.target" ];
              serviceConfig = hardening // {
                ExecStart = "${package}/bin/visitorstracker ${args} serve --host ${cfg.host} --port ${toString cfg.port}";
                Restart = "on-failure";
              };
            };
          };
        };
    };
}
