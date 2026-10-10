package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.ContainerEngineType;

/** Fixed Agent observations do not discover project tools or establish runtime admission. / 固定 Agent 观测不发现项目工具或建立运行时准入。 */
public final class DeploymentAgentHostProbe {
    private DeploymentAgentHostProbe() {
    }

    /** Renders only OS, architecture, package metadata and systemd command availability. / 仅渲染 OS、架构、包元数据与 systemd 命令可用性。 */
    public static String baseline() {
        return isolated(DeploymentPlatformCapabilityProbe.osReleaseReader() + """
            os_release=/etc/os-release
            if [ ! -f "$os_release" ] || [ -L "$os_release" ]; then os_release=/usr/lib/os-release; fi
            [ ! -L "$os_release" ] && probe_file "$os_release" || exit 64
            [ "$(/usr/bin/stat -c '%s' -- "$os_release")" -le 16384 ] || exit 64
            ID="$(os_release_value ID "$os_release")" || exit 64
            VARIANT_ID="$(os_release_value VARIANT_ID "$os_release")" || exit 64
            VERSION_ID="$(os_release_value VERSION_ID "$os_release")" || exit 64
            printf 'FORMAT=WTL_AGENT_BASELINE_1\\nDISTRO_ID=%s\\nDISTRO_VARIANT=%s\\nVERSION=%s\\n' "${ID:-unknown}" "${VARIANT_ID:-}" "${VERSION_ID:-unknown}"
            # Native OS queries use fixed system paths checked at the actual invocation.
            printf 'ARCH='
            [ -x /usr/bin/uname ] && probe_file /usr/bin/uname || exit 64
            /usr/bin/uname -m || exit 64
            if probe_command apt-get >/dev/null 2>&1; then printf 'PACKAGE_MANAGER=apt\\n';
            elif probe_command dnf >/dev/null 2>&1; then printf 'PACKAGE_MANAGER=dnf\\n';
            else printf 'PACKAGE_MANAGER=unknown\\n'; fi
            if [ -e /usr/bin/dpkg ] || [ -L /usr/bin/dpkg ]; then
              printf 'PACKAGE_ARCH='
              [ -x /usr/bin/dpkg ] && probe_file /usr/bin/dpkg || exit 64
              /usr/bin/dpkg --print-architecture || exit 64
            elif [ -e /usr/bin/rpm ] || [ -L /usr/bin/rpm ]; then
              printf 'PACKAGE_ARCH='
              [ -x /usr/bin/rpm ] && probe_file /usr/bin/rpm || exit 64
              /usr/bin/rpm --eval '%{_arch}' || exit 64
            else printf 'PACKAGE_ARCH=unknown\\n'; fi
            # SYSTEMD describes protected command availability; no daemon or runtime admission is inferred.
            if probe_command systemctl >/dev/null 2>&1; then printf 'SYSTEMD=1\\n'; else printf 'SYSTEMD=0\\n'; fi
            """);
    }

    /** Renders a separate observation of only the selected engine, including possible native-store initialization. / 单独渲染所选引擎观测，包含原生存储可能初始化的影响。 */
    public static String container(ContainerEngineType engine) {
        String body = switch (Objects.requireNonNull(engine)) {
            case DOCKER -> """
                client=0; operational=0
                if probe_command docker >/dev/null 2>&1; then
                  client=1
                  if HOME=/root docker info >/dev/null 2>&1; then operational=1; fi
                fi
                printf 'FORMAT=WTL_AGENT_CONTAINER_1\\nENGINE=DOCKER\\nCLIENT=%s\\nOPERATIONAL=%s\\nQUADLET=NOT_APPLICABLE\\n' "$client" "$operational"
                """;
            case PODMAN -> """
                client=0; operational=0; quadlet=0
                if probe_command podman >/dev/null 2>&1; then
                  client=1
                  if HOME=/root podman info >/dev/null 2>&1; then operational=1; fi
                  if HOME=/root podman quadlet --help >/dev/null 2>&1; then quadlet=1; fi
                fi
                printf 'FORMAT=WTL_AGENT_CONTAINER_1\\nENGINE=PODMAN\\nCLIENT=%s\\nOPERATIONAL=%s\\nQUADLET=%s\\n' "$client" "$operational" "$quadlet"
                """;
        };
        return isolated("# The selected engine info/quadlet query may initialize its native storage or configuration.\n" + body);
    }

    private static String isolated(String body) {
        return """
            exec /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LC_ALL=C \\
              HOME=/dev/null XDG_CONFIG_HOME=/dev/null XDG_CACHE_HOME=/dev/null XDG_DATA_HOME=/dev/null \\
              /bin/bash --noprofile --norc -s <<'WTL_AGENT_HOST_OBSERVATION'
            cd -- /
            """ + DeploymentPlatformCapabilityProbe.probeGuards() + "probe_parents / || exit 64\n" + body
            + "WTL_AGENT_HOST_OBSERVATION\n";
    }
}
