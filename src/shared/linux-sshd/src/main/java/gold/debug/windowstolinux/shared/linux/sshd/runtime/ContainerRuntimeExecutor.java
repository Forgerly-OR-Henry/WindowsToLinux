package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ApplicationHealthProbe;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Observes and health-checks only the named container owned by the managed release root.
 *
 *  <p>只观察和健康检查由受管发布根目录所有的命名容器。
 */
public final class ContainerRuntimeExecutor {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Creates the container runtime executor. / 创建容器运行时执行器。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ContainerRuntimeExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Performs a loopback health check while the named engine container remains running. / 在命名引擎容器持续运行时执行回环健康检查。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return constructed or resolved health check result / 构造或解析得到的健康检查结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public HealthCheckResult checkHealth(ManagedApplication application, DeploymentRuntimeSpecification.Container runtime,
                                         HealthCheck healthCheck) throws LinuxOperationException {
        return checkHealth(application, runtime.engine(), healthCheck);
    }

    /**
     * Performs a loopback health check for a remotely identified managed engine. / 为远端识别出的受管引擎执行回环健康检查。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtimeEngine runtime engine / 运行时引擎
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return constructed or resolved health check result / 构造或解析得到的健康检查结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public HealthCheckResult checkHealth(ManagedApplication application,
                                         DeploymentRuntimeSpecification.ContainerEngineType runtimeEngine,
                                         HealthCheck healthCheck) throws LinuxOperationException {
        return ApplicationHealthProbe.check(commands, application, healthCheck);
    }

    /**
     * Observes release-root ownership, engine state, and engine-specific autostart. / 观察发布根归属、引擎状态和引擎专属自启。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public LifecycleObservation observe(ManagedApplication application, DeploymentRuntimeSpecification.Container runtime)
            throws LinuxOperationException {
        return observe(application, runtime.engine());
    }

    /**
     * Observes a remotely identified managed container engine. / 观察远端识别出的受管容器引擎。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtimeEngine runtime engine / 运行时引擎
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public LifecycleObservation observe(ManagedApplication application,
                                        DeploymentRuntimeSpecification.ContainerEngineType runtimeEngine)
            throws LinuxOperationException {
        String engine = runtimeEngine.name().toLowerCase(java.util.Locale.ROOT);
        String root = application.releaseRoot();
        String name = "windowstolinux-" + application.id();
        String autostartCommand = runtimeEngine == DeploymentRuntimeSpecification.ContainerEngineType.DOCKER
                ? SshCommandExecutor.quote(engine) + " inspect --format '{{.HostConfig.RestartPolicy.Name}}' "
                + SshCommandExecutor.quote(name) + " 2>/dev/null || true"
                : "if test -f " + SshCommandExecutor.quote("/etc/containers/systemd/" + name
                + ".container.d/10-windowstolinux-autostart.conf") + "; then printf enabled; else printf no; fi";
        String script = """
                set -eu
                owner=0
                root=%s
                if [ -L "$root/current" ]; then
                  current=$(readlink -f "$root/current")
                  case "$current" in "$root/releases"/[0-9a-f]*)
                    if [ -f "$current/.windowstolinux-owner" ] && [ -f "$current/.windowstolinux-container-engine" ] \
                      && [ "$(cat "$current/.windowstolinux-owner")" = %s ] \
                      && [ "$(cat "$current/.windowstolinux-container-engine")" = %s ]; then owner=1; fi
                    ;;
                  esac
                fi
                running=$(%s inspect --format '{{.State.Running}}' %s 2>/dev/null || true)
                enabled=$(%s)
                printf 'OWNER=%%s\\nRUNNING=%%s\\nENABLED=%%s\\n' "$owner" "$running" "$enabled"
                """.formatted(SshCommandExecutor.quote(root), SshCommandExecutor.quote(application.ownershipManifestSha256()),
                SshCommandExecutor.quote(engine), SshCommandExecutor.quote(engine), SshCommandExecutor.quote(name), autostartCommand);
        var result = commands.exec("/bin/bash -lc " + SshCommandExecutor.quote(script), Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.RUNTIME_OBSERVATION_FAILED,
                    "Failed to observe the actual managed container state");
        }
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        boolean ownership = "1".equals(values.get("OWNER"));
        RuntimeState state = ownership && "true".equals(values.get("RUNNING")) ? RuntimeState.RUNNING
                : ownership ? RuntimeState.STOPPED : RuntimeState.UNKNOWN;
        String enabled = values.getOrDefault("ENABLED", "");
        AutostartState autostart = ownership && ("enabled".equals(enabled) || (!"no".equals(enabled) && !enabled.isBlank()))
                ? AutostartState.ENABLED : ownership ? AutostartState.DISABLED : AutostartState.UNKNOWN;
        return new LifecycleObservation(application, state, autostart, ownership, Instant.now(), ownership
                ? "Managed container ownership and engine state verified" : "Managed container ownership is missing or modified");
    }
}
