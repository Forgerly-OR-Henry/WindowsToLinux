package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.release;

import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input.DeploymentInputArguments;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input.ManagedContentArguments;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ContainerRuntimeArguments;

import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies the distinct Docker restart-policy and Podman Quadlet release protocol.
 *
 *  <p>应用彼此独立的 Docker 重启策略与 Podman Quadlet 发布协议。
 */
public final class ContainerReleaseProtocolExecutor {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Creates the container release protocol executor. / 创建容器发布协议执行器。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ContainerReleaseProtocolExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Captures a container rollback snapshot. / 捕获容器回滚快照。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved release snapshot / 构造或解析得到的发布快照
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public ReleaseSnapshot snapshot(ManagedApplication application, DeploymentRuntimeSpecification.Container runtime)
            throws LinuxOperationException {
        var result = commands.execProtocol(command("snapshot-container", application, runtime), Duration.ofSeconds(30), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.SNAPSHOT_IDENTITY_UNVERIFIED,
                    "Controlled helper could not verify the existing managed container: " + result.failureEvidence());
        }
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        if (!"1".equals(values.get("PREVIOUS"))) {
            return ReleaseSnapshot.firstDeployment("No previous managed container release exists");
        }
        String token = values.get("SNAPSHOT_TOKEN");
        if (token == null || !token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw LinuxOperationException.create(LinuxOperationFailureType.SNAPSHOT_TOKEN_MISSING,
                    "Controlled helper did not return a verifiable container rollback token");
        }
        return ReleaseSnapshot.withPreviousRelease(token, "1".equals(values.get("PREVIOUS_RUNNING")),
                "Controlled helper saved the previous managed container state");
    }

    /**
     * Publishes exactly one reviewed container. / 发布恰好一个经审阅的容器。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param build build / 构建
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param contentPublication content publication / 内容发布
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteStepResult publish(ManagedApplication application, RemoteWorkspace workspace, DeploymentBuildResult build,
                                    String releaseIdentity, DeploymentRuntimeSpecification.Container runtime,
                                    RemoteDeploymentInputs inputs, ManagedContentPublication contentPublication,
                                    ReleaseSnapshot snapshot) throws LinuxOperationException {
        if (!build.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.UNVERIFIED_BUILD_PUBLISH,
                    "An unverified container build cannot be published");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> values = new ArrayList<>(List.of(application.id(), workspace.candidateId(), releaseIdentity,
                application.ownershipManifestSha256()));
        values.add("identity-v2");
        values.add(runtime.identityPolicy().name());
        values.add(gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ApplicationWorkloadArguments.payload(runtime));
        values.addAll(DeploymentInputArguments.from(inputs));
        values.addAll(ManagedContentArguments.from(contentPublication));
        values.addAll(ContainerRuntimeArguments.from(runtime));
        return step("publish-container", values, "Controlled helper started the reviewed managed container");
    }

    /**
     * Rolls back a managed container. / 回滚受管容器。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param build build / 构建
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot,
                                     DeploymentBuildResult build, String releaseIdentity,
                                     DeploymentRuntimeSpecification.Container runtime, RemoteDeploymentInputs inputs)
            throws LinuxOperationException {
        if (!build.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.UNVERIFIED_BUILD_ROLLBACK,
                    "An unverified container build cannot be rolled back");
        }
        List<String> values = new ArrayList<>(List.of(application.id(), releaseIdentity,
                application.ownershipManifestSha256()));
        if (snapshot.hasPreviousRelease()) {
            values.add(snapshot.rollbackToken().orElseThrow());
            return step("rollback-container", values, "Controlled helper restored the previous managed container");
        }
        return step("rollback-container-first", values, "Controlled helper removed the failed first container release");
    }

    /**
     * Executes a container lifecycle action. / 执行容器生命周期动作。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteStepResult lifecycle(ManagedApplication application, String action) throws LinuxOperationException {
        List<String> values = new ArrayList<>(List.of(application.id(), action, application.ownershipManifestSha256()));
        return step("lifecycle-container", values, "Controlled helper executed the managed container lifecycle action");
    }

    /**
     * Renders a fixed helper invocation with individually quoted reviewed arguments; does not execute it.
     * <p>使用逐项引用的已审阅参数渲染固定 helper 调用，不执行该调用。
     *
     * @param verb verb / 操作动词
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return command text / 命令文本
     */
    private String command(String verb, ManagedApplication application, DeploymentRuntimeSpecification.Container runtime) {
        return helperCommand(verb, List.of(application.id(), application.ownershipManifestSha256()));
    }

    /**
     * Builds remote step result from the supplied step inputs.
     * <p>根据所提供步骤输入构建远端步骤结果。
     *
     * @param verb verb / 操作动词
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param successEvidence success evidence / 成功证据
     * @return remote step result from the supplied step inputs / 根据所提供步骤输入构建远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private RemoteStepResult step(String verb, List<String> values, String successEvidence) throws LinuxOperationException {
        var result = commands.exec(helperCommand(verb, values), Duration.ofSeconds(120), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(),
                result.succeeded() ? successEvidence : result.failureEvidence());
    }

    /**
     * Renders a fixed helper invocation with individually quoted reviewed arguments; does not execute it.
     * <p>使用逐项引用的已审阅参数渲染固定 helper 调用，不执行该调用。
     *
     * @param verb verb / 操作动词
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return helper command text / helper命令文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String helperCommand(String verb, List<String> values) {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(SshCommandExecutor.quote(ManagedHelperBundle.PATH)).append(' ')
                .append(SshCommandExecutor.quote(verb));
        for (String value : values) {
            command.append(' ').append(SshCommandExecutor.quote(Objects.requireNonNull(value, "helper argument")));
        }
        return command.toString();
    }
}
