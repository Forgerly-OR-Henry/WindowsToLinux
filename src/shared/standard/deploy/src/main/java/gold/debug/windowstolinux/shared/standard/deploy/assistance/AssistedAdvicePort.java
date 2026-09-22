package gold.debug.windowstolinux.shared.standard.deploy.assistance;

import java.util.*;

import gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice;

/** Deployment-purpose advice with credentials held by the caller. / 部署用途建议，凭据由调用方持有。 */
@FunctionalInterface
public interface AssistedAdvicePort {
    /** Requests advice at one fixed standard checkpoint. / 在一个固定标准节点请求建议。
     * @param phase analysis, preflight or failure / 分析、部署前或失败
     * @param candidates supplied nonsecret choices / 已提供的非秘密选项
     * @param evidence observed nonsecret facts / 已观察的非秘密事实
     * @return validated advice / 已校验建议
     * @throws Exception when advice is unavailable / 建议不可用时
     */
    AssistedDeploymentAdvice advise(String phase, Map<String, List<String>> candidates, Map<String, String> evidence)
            throws Exception;
}
