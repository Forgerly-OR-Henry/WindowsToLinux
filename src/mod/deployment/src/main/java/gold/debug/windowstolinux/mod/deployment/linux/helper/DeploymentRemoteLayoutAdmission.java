package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

/** Claims one host layout through the fixed recovery unit before managed-directory effects. */
public final class DeploymentRemoteLayoutAdmission {
    public static final String UNIT_PATH = "/etc/systemd/system/windowstolinux-workspace-recovery.service";
    public static final String UNIT_NAME = "windowstolinux-workspace-recovery.service";

    private DeploymentRemoteLayoutAdmission() {
    }

    public static String originalUnit() {
        return """
            [Unit]
            Description=WindowsToLinux temporary workspace recovery
            After=local-fs.target
            Before=multi-user.target
            [Service]
            Type=oneshot
            ExecStart=/usr/local/lib/windowstolinux/managed-helper workspace-recover
            [Install]
            WantedBy=multi-user.target
            """;
    }

    public static String unit(DeploymentRemoteLayout layout) {
        Objects.requireNonNull(layout);
        return """
            [Unit]
            Description=WindowsToLinux temporary workspace recovery
            After=local-fs.target
            Before=multi-user.target
            [Service]
            Type=oneshot
            ExecStart=%s workspace-recover
            [Install]
            WantedBy=multi-user.target
            # WTL_REMOTE_LAYOUT_V1=%s
            # WTL_REMOTE_LAYOUT_SHA256=%s
            """.formatted(layout.helperPath(), layout.canonical(), layout.digest());
    }

    public static List<String> ancestors(String path) {
        if (path == null || !path.matches("/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*"))
            throw new IllegalArgumentException("Invalid literal directory");
        var values = new ArrayList<String>();
        values.add("/");
        String current = "";
        for (String component : path.substring(1).split("/")) {
            if (component.equals(".") || component.equals(".."))
                throw new IllegalArgumentException("Directory traversal");
            current += "/" + component;
            values.add(current);
        }
        return List.copyOf(values);
    }

    public static String existingParents(String path) {
        var values = ancestors(path);
        return String.join(" ", values.subList(0, values.size() - 1).stream()
            .map(DeploymentHelperInstallerScript::quote).toList());
    }

    /** Checks existing roots and atomically claims the fixed unit; never overwrites another layout. */
    public static String initialize(DeploymentRemoteLayout layout) {
        var paths = Objects.requireNonNull(layout).paths();
        var roots = List.of(paths.helperDirectory(), paths.applicationsRoot(), paths.stateRoot(),
            paths.configurationRoot(), paths.dataRoot());
        var existing = roots.stream().flatMap(root -> ancestors(root).stream()).distinct().toList();
        String legacyRoots = DeploymentRemoteLayout.original().equals(layout) ? ""
            : """
                for wtl_layout_old_root in /usr/local/lib/windowstolinux /opt/windowstolinux/apps /var/lib/windowstolinux /etc/opt/windowstolinux/apps /var/opt/windowstolinux/apps; do
                    [ ! -e "$wtl_layout_old_root" ] && [ ! -L "$wtl_layout_old_root" ] || { printf 'PREPARE_REJECT=unbound-original-layout\\n'; exit 64; }
                done
                """;
        return "(\nset -euo pipefail\nwtl_layout_tmp=''\n"
            + "trap 'if [ -n \"$wtl_layout_tmp\" ]; then /usr/bin/rm -f -- \"$wtl_layout_tmp\"; fi' EXIT\n"
            + "[ \"$(id -u)\" = 0 ] || exit 64\n" + """
            wtl_layout_unit='%s'
            wtl_layout_directory() {
                [ -d "$1" ] && [ ! -L "$1" ] || return 1
                [ "$(/usr/bin/stat -c '%%u:%%g' -- "$1")" = 0:0 ] || return 1
                wtl_layout_mode=$(/usr/bin/stat -c '%%a' -- "$1") || return 1
                (( (8#$wtl_layout_mode & 022) == 0 ))
            }
            for wtl_layout_parent in / /etc /etc/systemd /etc/systemd/system; do
                wtl_layout_directory "$wtl_layout_parent" || { printf 'PREPARE_REJECT=layout-parent-ownership\\n'; exit 64; }
            done
            for wtl_layout_parent in %s; do
                if [ -e "$wtl_layout_parent" ] || [ -L "$wtl_layout_parent" ]; then
                    wtl_layout_directory "$wtl_layout_parent" || { printf 'PREPARE_REJECT=layout-root-ownership\\n'; exit 64; }
                fi
            done
            wtl_layout_loaded=$(/usr/bin/systemctl show '%s' --property=FragmentPath --value) || exit 64
            [ -z "$wtl_layout_loaded" ] || [ "$wtl_layout_loaded" = "$wtl_layout_unit" ] || { printf 'PREPARE_REJECT=layout-unit-fragment\\n'; exit 64; }
            wtl_layout_dropins=$(/usr/bin/systemctl show '%s' --property=DropInPaths --value) || exit 64
            [ -z "$wtl_layout_dropins" ] || { printf 'PREPARE_REJECT=layout-unit-dropins\\n'; exit 64; }
            if [ ! -e "$wtl_layout_unit" ] && [ ! -L "$wtl_layout_unit" ]; then
                :
            %s
            fi
            wtl_layout_tmp=$(/usr/bin/mktemp /etc/systemd/system/.windowstolinux-layout.XXXXXX) || exit 64
            cat > "$wtl_layout_tmp" <<'WTL_REMOTE_LAYOUT_UNIT_V1'
            %sWTL_REMOTE_LAYOUT_UNIT_V1
            /usr/bin/chown root:root -- "$wtl_layout_tmp"
            /usr/bin/chmod 644 -- "$wtl_layout_tmp"
            if [ ! -e "$wtl_layout_unit" ] && [ ! -L "$wtl_layout_unit" ]; then
                /usr/bin/ln -T -- "$wtl_layout_tmp" "$wtl_layout_unit" || :
            fi
            wtl_layout_valid=0
            if [ -f "$wtl_layout_unit" ] && [ ! -L "$wtl_layout_unit" ] && [ "$(/usr/bin/stat -c '%%u:%%g:%%a' -- "$wtl_layout_unit")" = 0:0:644 ]; then
                /usr/bin/cmp -s -- "$wtl_layout_unit" "$wtl_layout_tmp" && wtl_layout_valid=1
            fi
            /usr/bin/rm -- "$wtl_layout_tmp"
            wtl_layout_tmp=''
            if [ "$wtl_layout_valid" = 0 ] && [ '%s' = 1 ]; then
                wtl_layout_tmp=$(/usr/bin/mktemp /etc/systemd/system/.windowstolinux-layout.XXXXXX) || exit 64
                cat > "$wtl_layout_tmp" <<'WTL_ORIGINAL_LAYOUT_UNIT'
            %sWTL_ORIGINAL_LAYOUT_UNIT
                if [ -f "$wtl_layout_unit" ] && [ ! -L "$wtl_layout_unit" ] && [ "$(/usr/bin/stat -c '%%u:%%g:%%a' -- "$wtl_layout_unit")" = 0:0:644 ]; then
                    /usr/bin/cmp -s -- "$wtl_layout_unit" "$wtl_layout_tmp" && wtl_layout_valid=1
                fi
                /usr/bin/rm -- "$wtl_layout_tmp"
                wtl_layout_tmp=''
            fi
            [ "$wtl_layout_valid" = 1 ] || { printf 'PREPARE_REJECT=host-layout-conflict\\n'; exit 64; }
            """.formatted(UNIT_PATH, String.join(" ", existing), UNIT_NAME, UNIT_NAME, legacyRoots,
                unit(layout), DeploymentRemoteLayout.original().equals(layout) ? "1" : "0", originalUnit()) + ")\n";
    }
}
