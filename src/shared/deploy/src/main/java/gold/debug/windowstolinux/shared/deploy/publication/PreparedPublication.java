package gold.debug.windowstolinux.shared.deploy.publication;

import java.util.*;

import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.*;

/** Sealed candidate contract shared by both deployment engines. / 两种部署引擎共享的封存候选契约。
 * @param id graph-local component / 图内组件
 * @param dependencies required predecessors / 必需前置组件
 * @param application managed owner / 受管所有者
 * @param workspace task candidate / 任务候选
 * @param build verified build / 已验证构建
 * @param release exact release digest / 精确发布摘要
 * @param runtime managed execution description / 受管执行描述
 * @param standardFacts optional historical typed build evidence / 可选历史类型化构建证据
 * @param inputs sealed runtime inputs / 封存运行输入
 * @param resources explicit resource bindings / 显式资源绑定
 */
public record PreparedPublication(String id, List<String> dependencies, ManagedApplication application,
        RemoteWorkspace workspace, DeploymentBuildResult build, String release, DeploymentRuntimeSpecification runtime,
        Optional<DeploymentProjectFacts> standardFacts, RemoteDeploymentInputs inputs,
        ManagedContentPublication resources) {
    /** Revalidates ownership, build provenance and explicit execution. / 重新校验归属、构建来源及显式执行。
     * @param id component identity / 组件身份
     * @param dependencies dependency identities / 依赖身份
     * @param application managed owner / 受管所有者
     * @param workspace candidate / 候选
     * @param build build evidence / 构建证据
     * @param release release digest / 发布摘要
     * @param runtime execution contract / 执行契约
     * @param standardFacts optional typed evidence / 可选类型化证据
     * @param inputs runtime inputs / 运行输入
     * @param resources resource bindings / 资源绑定
     */
    public PreparedPublication {
        dependencies = List.copyOf(dependencies);
        Objects.requireNonNull(application);
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(build);
        Objects.requireNonNull(runtime);
        Objects.requireNonNull(standardFacts);
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(resources);
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,62}") || !release.matches("[0-9a-f]{64}")
                || !build.succeeded() || !application.id().equals(workspace.applicationId())
                || !workspace.sourceSha256().equals(build.sourceSha256())
                || standardFacts.isEmpty() && (!runtime.workload().reviewed()
                        || runtime.identityPolicy() == RuntimeIdentityMode.LEGACY_UNSPECIFIED))
            throw new IllegalArgumentException("unverified publication contract");
        if (standardFacts.isEmpty() && !(runtime instanceof DeploymentRuntimeSpecification.ManagedProcess)
                && !(runtime instanceof DeploymentRuntimeSpecification.Container))
            throw new IllegalArgumentException("historical runtime requires exact build evidence");
    }
}
