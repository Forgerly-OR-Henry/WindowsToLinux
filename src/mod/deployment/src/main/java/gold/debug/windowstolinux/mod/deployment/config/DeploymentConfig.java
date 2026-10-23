package gold.debug.windowstolinux.mod.deployment.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;

/** Immutable fixed settings for one deployment Mod activation. / 一次部署 Mod 启用的不可变固定配置。
 * @param source initial source admission limits / 初始来源准入限额
 * @param build initial reviewed build limits / 初始构建审阅限额
 * @param environment environment preparation timing / 环境准备时序
 * @param view feedback and pagination settings / 反馈与分页配置
 * @param ai requested output budgets / 申请输出额度
 * @param paths remote layout captured by deployments and retained operations / 部署及保留操作捕获的远端布局配置 */
public record DeploymentConfig(Source source, Build build, Environment environment, View view, Ai ai,
                               RemotePaths paths) {
    /** Requires all six configuration groups. / 要求六组配置齐全。
     * @param source source limits / 来源限额
     * @param build build defaults / 构建默认值
     * @param environment environment timing / 环境时序
     * @param view presentation settings / 展示配置
     * @param ai output budgets / 输出额度
     * @param paths remote layout settings / 远端布局配置 */
    public DeploymentConfig {
        Objects.requireNonNull(source);
        Objects.requireNonNull(build);
        Objects.requireNonNull(environment);
        Objects.requireNonNull(view);
        Objects.requireNonNull(ai);
        Objects.requireNonNull(paths);
    }

    /** Literal, separate remote roots without filesystem normalization. / 不做文件系统归一化的字面独立远端根。
     * @param helperDirectory helper and toolchain directory / helper 与工具链目录
     * @param applicationsRoot managed applications root / 受管应用根
     * @param stateRoot operation evidence and workspace root / 操作依据与工作区根
     * @param configurationRoot configuration and secret root / 配置与秘密根
     * @param dataRoot mutable application data root / 应用可变数据根 */
    public record RemotePaths(String helperDirectory, String applicationsRoot, String stateRoot,
                              String configurationRoot, String dataRoot) {
        private static final Set<String> SYSTEM_DIRECTORIES = Set.of("/bin", "/boot", "/etc", "/etc/opt",
            "/home", "/lib", "/lib64", "/media", "/mnt", "/opt", "/root", "/sbin", "/srv", "/usr",
            "/usr/bin", "/usr/lib", "/usr/lib64", "/usr/local", "/usr/local/bin", "/usr/local/lib",
            "/usr/local/sbin", "/usr/sbin", "/usr/share", "/var", "/var/cache", "/var/lib", "/var/log",
            "/var/opt");

        private static final List<String> CONTROL_TREES = List.of("/proc", "/sys", "/dev", "/run", "/tmp",
            "/var/tmp", "/etc/systemd", "/etc/containers");

        public RemotePaths {
            requireRoot(helperDirectory);
            requireRoot(applicationsRoot);
            requireRoot(stateRoot);
            requireRoot(configurationRoot);
            requireRoot(dataRoot);
            String[] roots = {helperDirectory, applicationsRoot, stateRoot, configurationRoot, dataRoot};
            for (int first = 0; first < roots.length; first++) {
                for (int second = first + 1; second < roots.length; second++) {
                    if (within(roots[first], roots[second]) || within(roots[second], roots[first]))
                        throw new IllegalArgumentException("Remote deployment roots must not overlap");
                }
            }
        }

        /** Original fixed layout values, never a loader fallback. / 原固定布局值，绝非加载回退。
         * @return immutable original remote roots / 不可变原远端根 */
        public static RemotePaths original() {
            return new RemotePaths("/usr/local/lib/windowstolinux", "/opt/windowstolinux/apps",
                "/var/lib/windowstolinux", "/etc/opt/windowstolinux/apps", "/var/opt/windowstolinux/apps");
        }

        private static void requireRoot(String path) {
            Objects.requireNonNull(path, "remote root");
            if (!path.startsWith("/") || !path.equals(DeploymentStorageLocation.validatedPath(path))
                || SYSTEM_DIRECTORIES.contains(path) || CONTROL_TREES.stream().anyMatch(tree -> within(path, tree)))
                throw new IllegalArgumentException("Remote deployment root must be a dedicated canonical literal Linux path");
        }

        private static boolean within(String path, String root) {
            return path.equals(root) || path.startsWith(root + "/");
        }
    }

    /** Requested budgets; the APP's output policy determines the actual provider limit. */
    public record Ai(int deploymentMaxOutputTokens, int extendedDeploymentMaxOutputTokens,
                     int approvalMaxOutputTokens, Requests requests, Execution execution, History history) {
        public Ai {
            if (deploymentMaxOutputTokens < 1 || extendedDeploymentMaxOutputTokens < deploymentMaxOutputTokens
                || approvalMaxOutputTokens < 1 || approvalMaxOutputTokens > extendedDeploymentMaxOutputTokens)
                throw new IllegalArgumentException("Invalid deployment AI output budgets");
            Objects.requireNonNull(requests);
            Objects.requireNonNull(execution);
            Objects.requireNonNull(history);
        }
    }

    /** Caller-owned request deadlines and an optional Agent pure-generation recovery budget; unrelated to project size, tool attempts or execution permission.
     * @param deploymentTimeoutSeconds ordinary logical generation deadline / 普通逻辑生成期限
     * @param approvalTimeoutSeconds approval logical generation deadline / 审批逻辑生成期限
     * @param extendedTimeoutSeconds extended logical generation deadline / 扩展逻辑生成期限
     * @param generationRecoveryAttempts extra incomplete-body generation attempts for independently selected Agent callers, 0..16; 0 disables / 独立 Agent 调用者选择的正文不完整额外生成次数，0..16；0 关闭 */
    public record Requests(int deploymentTimeoutSeconds, int approvalTimeoutSeconds, int extendedTimeoutSeconds,
                           int generationRecoveryAttempts) {
        public Requests(int deploymentTimeoutSeconds, int approvalTimeoutSeconds, int extendedTimeoutSeconds) {
            this(deploymentTimeoutSeconds, approvalTimeoutSeconds, extendedTimeoutSeconds, 0);
        }

        public Requests {
            if (generationRecoveryAttempts < 0 || generationRecoveryAttempts > 16)
                throw new IllegalArgumentException("Generation recovery attempts must be in 0..16");
            if (deploymentTimeoutSeconds < 1 || approvalTimeoutSeconds < 1 || extendedTimeoutSeconds < 1
                || extendedTimeoutSeconds > 86400 || extendedTimeoutSeconds < deploymentTimeoutSeconds
                || extendedTimeoutSeconds < approvalTimeoutSeconds)
                throw new IllegalArgumentException("Invalid deployment AI request deadlines");
        }
    }

    /** Finite task resource ceilings; models decide whether to continue without filling these budgets. */
    public record Execution(int sharedToolAttempts, int standaloneToolAttempts, int localToolTurns,
                            int agentModelResponses, int retainedReviewSessions, int maxObservations) {
        public Execution {
            if (sharedToolAttempts < 1 || standaloneToolAttempts < 1 || localToolTurns < 1 || agentModelResponses < 1
                || retainedReviewSessions < 1 || standaloneToolAttempts > sharedToolAttempts || localToolTurns > standaloneToolAttempts
                || retainedReviewSessions > sharedToolAttempts || agentModelResponses > Integer.MAX_VALUE / 4
                || maxObservations < 1 || maxObservations > 999999)
                throw new IllegalArgumentException("Invalid deployment AI execution budgets");
        }
    }

    /** Whole-batch history limits within the unchanged non-vision 1 MiB request boundary. */
    public record History(int maxBatches, int characters, int payloadBytes, int initialBatches,
                          int initialPayloadBytes) {
        public History {
            if (maxBatches < 1 || characters < 1 || characters > 1024 * 1024 || payloadBytes < 1 || payloadBytes > 1024 * 1024
                || initialBatches < 1 || initialBatches > maxBatches || initialPayloadBytes < 1 || initialPayloadBytes > payloadBytes)
                throw new IllegalArgumentException("Invalid deployment AI history budgets");
        }
    }

    /** Source defaults bounded by the existing admission contract. / 受既有准入合同约束的来源默认值。
     * @param bytes maximum admitted source bytes / 来源字节上限
     * @param entries maximum discovered entries / 发现条目上限
     * @param archiveBytes maximum compressed archive bytes / 压缩归档字节上限 */
    public record Source(long bytes, int entries, long archiveBytes) {
        /** Rejects limits outside the original source boundary. / 拒绝超出原来源边界的限额。
         * @param bytes source bytes / 来源字节
         * @param entries discovered entries / 发现条目
         * @param archiveBytes archive bytes / 归档字节 */
        public Source {
            if (bytes < 1 || bytes > 2L * 1024 * 1024 * 1024 || entries < 1 || entries > 100_000
                || archiveBytes < 1 || archiveBytes > 2L * 1024 * 1024 * 1024)
                throw new IllegalArgumentException("Invalid deployment source defaults");
        }
    }

    /** Initial non-root build values; user review still captures the final limits. / 初始非 root 构建值；最终限额仍由用户审阅捕获。
     * @param timeoutSeconds command waiting limit / 命令等待上限
     * @param processes process count limit / 进程数量上限
     * @param memoryMiB memory limit in MiB / 内存 MiB 上限
     * @param outputBytes captured output limit / 捕获输出上限
     * @param workspaceBytes build workspace limit / 构建工作目录上限 */
    public record Build(int timeoutSeconds, int processes, int memoryMiB, long outputBytes, long workspaceBytes,
                        long workspaceMaxBytes, long workspaceHostReserveBytes) {
        public Build(int timeoutSeconds, int processes, int memoryMiB, long outputBytes, long workspaceBytes) {
            this(timeoutSeconds, processes, memoryMiB, outputBytes, workspaceBytes, 128L * 1024 * 1024 * 1024, 1024L * 1024 * 1024);
        }

        /** Applies the existing build validation without granting root execution. / 应用既有构建校验，不授予 root 执行。
         * @param timeoutSeconds waiting seconds / 等待秒数
         * @param processes process limit / 进程上限
         * @param memoryMiB memory MiB / 内存 MiB
         * @param outputBytes output bytes / 输出字节
         * @param workspaceBytes workspace bytes / 工作目录字节 */
        public Build {
            new DeploymentBuildLimits(timeoutSeconds, processes, memoryMiB, outputBytes, workspaceBytes, false);
            new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Policy(workspaceMaxBytes, workspaceHostReserveBytes);
        }

        /** Creates the initial values presented for explicit review. / 创建供明确审阅的初始值。
         * @return initial non-root build limits / 初始非 root 构建限额 */
        public gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Policy workspacePolicy() {
            return new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Policy(workspaceMaxBytes, workspaceHostReserveBytes);
        }

        public DeploymentBuildLimits limits() {
            return new DeploymentBuildLimits(timeoutSeconds, processes, memoryMiB, outputBytes, workspaceBytes, false, workspaceHostReserveBytes);
        }
    }

    /** Timing retained by the original environment task. / 原环境任务保留的时序。
     * @param installSeconds package command timeout / 软件包命令时限
     * @param checkSeconds other fixed command timeout / 其他固定命令时限
     * @param rebootSeconds reboot observation budget / 重启观察预算
     * @param pollSeconds reboot observation spacing / 重启观察间隔 */
    public record Environment(int installSeconds, int checkSeconds, int rebootSeconds, int pollSeconds) {
        /** Requires bounded positive timing and a meaningful polling interval. / 要求有界正时序及有效轮询间隔。
         * @param installSeconds package timeout / 软件包时限
         * @param checkSeconds check timeout / 检查时限
         * @param rebootSeconds observation budget / 观察预算
         * @param pollSeconds polling spacing / 轮询间隔 */
        public Environment {
            if (installSeconds < 1 || installSeconds > 7200 || checkSeconds < 1 || checkSeconds > 7200
                || rebootSeconds < 1 || rebootSeconds > 7200 || pollSeconds < 1 || pollSeconds > rebootSeconds)
                throw new IllegalArgumentException("Invalid deployment environment timing");
        }

        /** Preserves timing of retained tasks created before configuration capture existed. / 保留配置捕获引入前既有任务的时序。
         * @return historical fixed timing, independent of the current YAML / 独立于当前 YAML 的历史固定时序 */
        public static Environment original() {
            return new Environment(900, 30, 900, 5);
        }
    }

    /** Feedback refresh and platform-bounded page capacity. / 反馈刷新及受平台约束的分页容量。
     * @param refreshMillis refresh interval in milliseconds / 刷新间隔毫秒
     * @param pageSize page capacity / 分页容量 */
    public record View(int refreshMillis, int pageSize) {
        /** Prevents busy polling and respects the platform's 200-item ceiling. / 防止忙轮询并遵守平台 200 条上限。
         * @param refreshMillis refresh milliseconds / 刷新毫秒
         * @param pageSize page capacity / 分页容量 */
        public View {
            if (refreshMillis < 100 || refreshMillis > 60_000 || pageSize < 1 || pageSize > 200)
                throw new IllegalArgumentException("Invalid deployment view settings");
        }
    }
}
