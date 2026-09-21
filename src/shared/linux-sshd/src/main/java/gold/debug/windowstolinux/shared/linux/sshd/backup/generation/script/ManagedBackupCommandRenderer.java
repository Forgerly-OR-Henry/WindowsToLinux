package gold.debug.windowstolinux.shared.linux.sshd.backup.generation.script;

import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifact;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactKind;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactRequest;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Renders only fixed managed-backup helper verbs and validated scalar arguments. / 仅渲染固定受管备份 helper 动词及已校验标量参数。
 */
public final class ManagedBackupCommandRenderer {
    /**
     * Renders exact remote artifact creation. / 渲染精确远端制品创建。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return create text / 创建文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public String create(RemoteBackupArtifactRequest request) {
        Objects.requireNonNull(request, "request");
        return command("backup-create", List.of(request.operationId(), request.applicationId(), request.componentId(),
                request.managedApplication().id(), request.releaseSha256(),
                request.managedApplication().ownershipManifestSha256(), kind(request.kind()), request.resourceId(),
                Long.toString(request.maximumBytes())));
    }

    /**
     * Renders verified artifact streaming. / 渲染校验制品流式回读。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @return read text / 读取文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public String read(RemoteBackupArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return command("backup-read", List.of(artifact.operationId(), artifact.artifactId(), kind(artifact.kind()),
                Long.toString(artifact.byteCount()), artifact.sha256()));
    }

    /**
     * Renders exact operation cleanup. / 渲染精确操作清理。
     *
     * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
     * @return discard text / 丢弃文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public String discard(String operationId) {
        return command("backup-discard", List.of(Objects.requireNonNull(operationId, "operationId")));
    }

    /**
     * Maps the backup artifact kind to its fixed helper command token.
     * <p>将备份制品类型映射为固定 helper 命令令牌。
     *
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @return kind text / 种类文本
     */
    private static String kind(RemoteBackupArtifactKind kind) {
        return switch (kind) {
            case FILE_TREE -> "file";
            case RELEASE_TREE -> "release";
            case VOLUME -> "volume";
            case OCI_IMAGE -> "oci";
        };
    }

    /**
     * Renders a fixed helper invocation with individually quoted reviewed arguments; does not execute it.
     * <p>使用逐项引用的已审阅参数渲染固定 helper 调用，不执行该调用。
     *
     * @param verb verb / 操作动词
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return command text / 命令文本
     */
    private static String command(String verb, List<String> values) {
        List<String> all = new ArrayList<>(values.size() + 2);
        all.add("sudo"); all.add("-n"); all.add(ManagedHelperBundle.PATH); all.add(verb); all.addAll(values);
        return all.stream().map(SshCommandExecutor::quote).collect(java.util.stream.Collectors.joining(" "));
    }
}
