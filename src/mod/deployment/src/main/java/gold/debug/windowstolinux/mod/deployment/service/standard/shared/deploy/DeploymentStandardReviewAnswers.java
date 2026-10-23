package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;

/** Frozen non-secret answers for one analyzed component; consent is supplied by the actual reviewer. / 一个已分析组件的冻结非秘密答案；确认记录由真实审阅者提供。
 * @param values reviewed runtime, configuration and database notation / 经审阅运行时、配置及数据库记法
 * @param files reviewed managed file bindings / 经审阅受管文件绑定
 * @param configurationRevision immutable configuration revision / 不可变配置修订
 * @param limits reviewed build limits / 经审阅构建限额
 * @param consent actual source and target confirmation / 真实来源与目标确认
 * @param containerDaemonRiskAccepted explicit container daemon risk decision / 容器守护进程风险的显式决定
 * @param experimentalAdapterRiskAccepted explicit adapter risk decision / 适配器风险的显式决定 */
public record DeploymentStandardReviewAnswers(Map<String, String> values, List<DeploymentFileBinding> files,
                                              long configurationRevision, DeploymentBuildLimits limits,
                                              DeploymentReviewConsent consent,
                                              boolean containerDaemonRiskAccepted,
                                              boolean experimentalAdapterRiskAccepted) {
    /** Copies all submitted answers without turning their existence into approval. / 复制已提交答案，不把存在本身视为批准。
     * @param values reviewed non-secret text / 经审阅不含秘密文本
     * @param files managed files / 受管文件
     * @param configurationRevision immutable revision / 不可变修订
     * @param limits build limits / 构建限额
     * @param consent actual review / 真实审阅
     * @param containerDaemonRiskAccepted container decision / 容器决定
     * @param experimentalAdapterRiskAccepted adapter decision / 适配器决定 */
    public DeploymentStandardReviewAnswers {
        values = Map.copyOf(values);
        files = List.copyOf(files);
        if (configurationRevision < 1)
            throw new IllegalArgumentException("Configuration revision must be positive");
        Objects.requireNonNull(limits);
        Objects.requireNonNull(consent);
    }
}
