package gold.debug.windowstolinux.mod.deployment.config;

import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentBuildLimits;

/** Immutable fixed settings for one deployment Mod activation. / 一次部署 Mod 启用的不可变固定配置。
 * @param source initial source admission limits / 初始来源准入限额
 * @param build initial reviewed build limits / 初始构建审阅限额
 * @param environment environment preparation timing / 环境准备时序
 * @param view feedback and pagination settings / 反馈与分页配置
 * @param ai requested output budgets / 申请输出额度 */
public record DeploymentConfig(Source source, Build build, Environment environment, View view, Ai ai) {
    /** Requires all five configuration groups. / 要求五组配置齐全。
     * @param source source limits / 来源限额
     * @param build build defaults / 构建默认值
     * @param environment environment timing / 环境时序
     * @param view presentation settings / 展示配置
     * @param ai output budgets / 输出额度 */
    public DeploymentConfig {
        Objects.requireNonNull(source);
        Objects.requireNonNull(build);
        Objects.requireNonNull(environment);
        Objects.requireNonNull(view);
        Objects.requireNonNull(ai);
    }

    /** Requested budgets; the APP's output policy determines the actual provider limit. */
    public record Ai(int deploymentMaxOutputTokens, int extendedDeploymentMaxOutputTokens,
                     int approvalMaxOutputTokens) {
        public Ai {
            if (deploymentMaxOutputTokens < 1 || extendedDeploymentMaxOutputTokens < deploymentMaxOutputTokens
                || approvalMaxOutputTokens < 1 || approvalMaxOutputTokens > deploymentMaxOutputTokens)
                throw new IllegalArgumentException("Invalid deployment AI output budgets");
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
    public record Build(int timeoutSeconds, int processes, int memoryMiB, long outputBytes, long workspaceBytes) {
        /** Applies the existing build validation without granting root execution. / 应用既有构建校验，不授予 root 执行。
         * @param timeoutSeconds waiting seconds / 等待秒数
         * @param processes process limit / 进程上限
         * @param memoryMiB memory MiB / 内存 MiB
         * @param outputBytes output bytes / 输出字节
         * @param workspaceBytes workspace bytes / 工作目录字节 */
        public Build {
            new DeploymentBuildLimits(timeoutSeconds, processes, memoryMiB, outputBytes, workspaceBytes, false);
        }

        /** Creates the initial values presented for explicit review. / 创建供明确审阅的初始值。
         * @return initial non-root build limits / 初始非 root 构建限额 */
        public DeploymentBuildLimits limits() {
            return new DeploymentBuildLimits(timeoutSeconds, processes, memoryMiB, outputBytes, workspaceBytes, false);
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
