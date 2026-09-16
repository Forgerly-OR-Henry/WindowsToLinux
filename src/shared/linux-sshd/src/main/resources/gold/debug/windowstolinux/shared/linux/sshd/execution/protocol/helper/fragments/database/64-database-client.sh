# SQL clients receive one private credential and no host-management privilege.
run_mysql_client() {
  local client="$1" defaults="$2" unit
  unit="windowstolinux-database-$(cat /proc/sys/kernel/random/uuid).service"
  shift 2
  case "$client" in /usr/bin/mysql|/usr/bin/mariadb|/usr/bin/mysqldump|/usr/bin/mariadb-dump) ;; *) reject database-client-path ;; esac
  assert_root_owned_regular "$defaults"
  local -a flags
  flags=()
  case "$client" in
    /usr/bin/mysql|/usr/bin/mariadb) flags=(--batch --binary-mode --local-infile=0) ;;
  esac
  systemd-run --quiet --wait --pipe --collect --unit="$unit" \
    --property=DynamicUser=yes --property=NoNewPrivileges=yes --property=CapabilityBoundingSet= \
    --property=ProtectSystem=strict --property=ProtectHome=yes --property=PrivateTmp=yes \
    --property=PrivateDevices=yes --property=RestrictSUIDSGID=yes --property=RestrictNamespaces=yes \
    --property=ProtectControlGroups=yes --property=ProtectKernelTunables=yes \
    --property=KillMode=control-group --property=TimeoutStopSec=5s --property=RuntimeMaxSec=1800 \
    --property=MemoryMax=512M --property=TasksMax=64 --property=UMask=0077 \
    --property="LoadCredential=client:$defaults" --property=WorkingDirectory=/ \
    --property="InaccessiblePaths=-$base_root -/run/systemd -/run/dbus -/run/docker.sock -/run/podman" \
    "$client" "--defaults-file=/run/credentials/$unit/client" "${flags[@]}" "$@"
}
