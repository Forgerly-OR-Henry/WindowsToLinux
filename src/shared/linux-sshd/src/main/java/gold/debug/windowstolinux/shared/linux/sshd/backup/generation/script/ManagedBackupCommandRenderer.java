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

/** Renders only fixed managed-backup helper verbs and validated scalar arguments. / 仅渲染固定受管备份 helper 动词及已校验标量参数。 */
public final class ManagedBackupCommandRenderer {
    /** Renders exact remote artifact creation. / 渲染精确远端制品创建。 */
    public String create(RemoteBackupArtifactRequest request) {
        Objects.requireNonNull(request, "request");
        return command("backup-create", List.of(request.operationId(), request.applicationId(), request.componentId(),
                request.managedApplication().id(), request.releaseSha256(),
                request.managedApplication().ownershipManifestSha256(), kind(request.kind()), request.resourceId(),
                Long.toString(request.maximumBytes())));
    }

    /** Renders verified artifact streaming. / 渲染校验制品流式回读。 */
    public String read(RemoteBackupArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return command("backup-read", List.of(artifact.operationId(), artifact.artifactId(), kind(artifact.kind()),
                Long.toString(artifact.byteCount()), artifact.sha256()));
    }

    /** Renders exact operation cleanup. / 渲染精确操作清理。 */
    public String discard(String operationId) {
        return command("backup-discard", List.of(Objects.requireNonNull(operationId, "operationId")));
    }

    private static String kind(RemoteBackupArtifactKind kind) {
        return switch (kind) {
            case FILE_TREE -> "file";
            case RELEASE_TREE -> "release";
            case VOLUME -> "volume";
            case OCI_IMAGE -> "oci";
        };
    }

    private static String command(String verb, List<String> values) {
        List<String> all = new ArrayList<>(values.size() + 2);
        all.add("sudo"); all.add("-n"); all.add(ManagedHelperBundle.PATH); all.add(verb); all.addAll(values);
        return all.stream().map(SshCommandExecutor::quote).collect(java.util.stream.Collectors.joining(" "));
    }
}
