package gold.debug.windowstolinux.shared.linux.sshd.execution.transfer;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreFilePort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreMember;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingEvidence;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingRequest;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.CandidateWorkspaceExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** SFTP restore staging with exact local inventory and independent remote read-back. / 使用精确本地清单和独立远端回读的 SFTP 恢复暂存。 */
public final class SshdRestoreTransport implements RemoteRestoreFilePort {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final ClientSession session;
    private final CandidateWorkspaceExecutor candidates;

    /** Creates an isolated restore transport. / 创建隔离恢复传输器。 */
    public SshdRestoreTransport(ClientSession session, CandidateWorkspaceExecutor candidates) {
        this.session = Objects.requireNonNull(session, "session");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    /** Verifies, uploads, re-reads and re-verifies every exact member. / 校验、上传、回读并再次校验每个精确成员。 */
    @Override
    public RemoteRestoreStagingEvidence stageRestoreFiles(RemoteRestoreStagingRequest request)
            throws LinuxOperationException {
        Objects.requireNonNull(request, "request");
        verifyLocalCandidate(request);
        RemoteWorkspace workspace = new RemoteWorkspace(request.applicationId(), request.archiveSha256());
        RemoteStepResult prepared = candidates.createRestore(workspace);
        if (!prepared.succeeded()) {
            throw LinuxOperationException.create(
                    LinuxOperationFailureType.CANDIDATE_PREPARATION_FAILED, prepared.evidence());
        }
        String remoteRootText = workspace.candidateRoot() + "/mutable/restore";
        try (SftpFileSystem fileSystem = SftpClientFactory.instance().createSftpFileSystem(session)) {
            Path remoteRoot = fileSystem.getPath(remoteRootText).normalize();
            Files.createDirectories(remoteRoot);
            for (RemoteRestoreMember member : request.members()) {
                Path local = localPath(request, member);
                Path remote = remoteRoot.resolve(member.path()).normalize();
                if (!remote.startsWith(remoteRoot)) {
                    throw new IOException("restore member escaped the remote candidate root");
                }
                Files.createDirectories(remote.getParent());
                Files.copy(local, remote, StandardCopyOption.REPLACE_EXISTING);
            }
            verifyRemoteCandidate(request, remoteRoot);
            verifyLocalCandidate(request);
        } catch (LinuxOperationException exception) {
            cleanupAfterFailure(workspace);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            cleanupAfterFailure(workspace);
            throw LinuxOperationException.create(LinuxOperationFailureType.RESTORE_UPLOAD_FAILED,
                    "SFTP restore candidate staging failed", exception);
        }
        return new RemoteRestoreStagingEvidence(request.candidateId(), remoteRootText,
                request.archiveSha256().substring(0, 32), request.expectedBytes(), true, true, true,
                List.of("Every manifest member was staged under the digest-derived candidate",
                        "Every remote member was independently read back through SFTP",
                        "The existing managed release path was not addressed by the staging operation"));
    }

    /** Discards the exact candidate through the existing fixed helper verb. / 通过现有固定 helper 动词丢弃精确候选。 */
    @Override
    public RemoteStepResult discardRestoreFiles(RemoteRestoreStagingRequest request) throws LinuxOperationException {
        Objects.requireNonNull(request, "request");
        return candidates.cleanup(new RemoteWorkspace(request.applicationId(), request.archiveSha256()));
    }

    private static void verifyLocalCandidate(RemoteRestoreStagingRequest request) throws LinuxOperationException {
        Path root = request.localCandidateRoot();
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                throw invalidLocal("restore candidate root is not a regular non-link directory", null);
            }
            Set<String> actual = new HashSet<>();
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path path : stream.toList()) {
                    if (path.equals(root)) continue;
                    if (Files.isSymbolicLink(path)) throw invalidLocal("restore candidate contains a link", null);
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw invalidLocal("restore candidate contains a non-regular member", null);
                    }
                    actual.add(root.relativize(path).toString().replace('\\', '/'));
                }
            }
            Set<String> expected = request.members().stream().map(RemoteRestoreMember::path)
                    .collect(java.util.stream.Collectors.toSet());
            if (!actual.equals(expected)) throw invalidLocal("restore candidate files differ from the manifest", null);
            for (RemoteRestoreMember member : request.members()) {
                ContentDigest digest = digest(Files.newInputStream(localPath(request, member)));
                if (digest.size() != member.size() || !digest.sha256().equals(member.sha256())) {
                    throw invalidLocal("restore candidate content differs from the manifest", null);
                }
            }
        } catch (LinuxOperationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidLocal("restore candidate could not be verified", exception);
        }
    }

    private static void verifyRemoteCandidate(RemoteRestoreStagingRequest request, Path remoteRoot)
            throws LinuxOperationException {
        try {
            for (RemoteRestoreMember member : request.members()) {
                Path remote = remoteRoot.resolve(member.path()).normalize();
                if (!remote.startsWith(remoteRoot) || Files.isSymbolicLink(remote)
                        || !Files.isRegularFile(remote, LinkOption.NOFOLLOW_LINKS)) {
                    throw verificationFailure("remote restore member is not one regular candidate file", null);
                }
                ContentDigest digest = digest(Files.newInputStream(remote));
                if (digest.size() != member.size() || !digest.sha256().equals(member.sha256())) {
                    throw verificationFailure("remote restore member digest or size differs", null);
                }
            }
        } catch (LinuxOperationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw verificationFailure("remote restore candidate read-back failed", exception);
        }
    }

    private static Path localPath(RemoteRestoreStagingRequest request, RemoteRestoreMember member)
            throws LinuxOperationException {
        Path path = request.localCandidateRoot().resolve(member.path()).normalize();
        if (!path.startsWith(request.localCandidateRoot())) {
            throw invalidLocal("restore member escaped the local candidate root", null);
        }
        return path;
    }

    private static ContentDigest digest(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                size = Math.addExact(size, read);
                digest.update(buffer, 0, read);
            }
        }
        return new ContentDigest(size, HexFormat.of().formatHex(digest.digest()));
    }

    private void cleanupAfterFailure(RemoteWorkspace workspace) {
        try {
            candidates.cleanup(workspace);
        } catch (LinuxOperationException ignored) {
            // The staging failure remains primary; the restore coordinator verifies cleanup separately. / 暂存失败保持首要；恢复协调器会单独验证清理。
        }
    }

    private static LinuxOperationException invalidLocal(String diagnostic, Throwable cause) {
        return LinuxOperationException.create(LinuxOperationFailureType.RESTORE_SOURCE_INVALID, diagnostic, cause);
    }

    private static LinuxOperationException verificationFailure(String diagnostic, Throwable cause) {
        return LinuxOperationException.create(
                LinuxOperationFailureType.RESTORE_UPLOAD_VERIFICATION_FAILED, diagnostic, cause);
    }

    private record ContentDigest(long size, String sha256) {
    }
}
