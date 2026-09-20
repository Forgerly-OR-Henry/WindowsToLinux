package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretCryptoService;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretDocument;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretFailureType;
import gold.debug.windowstolinux.app.windows.workspace.WindowsRestoreAttempt;
import gold.debug.windowstolinux.app.windows.workspace.WindowsRestoreWorkspace;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException;
import gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceFailureType;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchivePolicy;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidator;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.restore.BackupArchiveExtractor;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreCandidate;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Local inspection and isolated extraction use case that performs no remote mutation. / 不执行远端修改的本地检查与隔离提取用例。 */
public final class BackupUseCase {
    private final BackupArchiveValidator validator;
    private final BackupArchiveExtractor extractor;
    private final WindowsRestoreWorkspace workspace;
    private final BackupSecretCryptoService secrets;

    /** Creates the desktop backup use case over the platform-owned workspace. / 基于平台持有工作区创建桌面备份用例。 */
    public BackupUseCase(Path workDirectory) {
        this(new BackupArchiveValidator(BackupArchivePolicy.defaults()), new BackupArchiveExtractor(),
                new WindowsRestoreWorkspace(workDirectory), new BackupSecretCryptoService());
    }

    BackupUseCase(
            BackupArchiveValidator validator,
            BackupArchiveExtractor extractor,
            WindowsRestoreWorkspace workspace,
            BackupSecretCryptoService secrets
    ) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /** Fully validates one selected archive without extracting or connecting. / 完整校验一个已选归档且不提取、不连接。 */
    public BackupArchiveInspection inspect(Path archive) throws BackupException {
        return BackupArchiveInspection.from(validator.validate(archive));
    }

    /** Revalidates and extracts one new local candidate without activating it. / 重新校验并提取一个新的本地候选且不激活。 */
    public PreparedBackupCandidate prepare(Path archive) throws IOException {
        return candidate(prepareValidated(archive));
    }

    /** Deletes only the exact platform-owned attempt containing this prepared candidate. / 仅删除包含此已准备候选的精确平台持有尝试。 */
    public void discard(PreparedBackupCandidate candidate) throws WindowsWorkspaceException {
        Objects.requireNonNull(candidate, "candidate");
        WindowsRestoreAttempt attempt = candidate.attempt();
        if (attempt == null) {
            throw WindowsWorkspaceException.create(
                    WindowsWorkspaceFailureType.RESTORE_WORKSPACE_FAILED,
                    "The local restore candidate does not carry platform-issued cleanup authority", null);
        }
        workspace.discardAttempt(attempt);
    }

    /** Prepares a candidate and returns only a complete manifest-bound decoded secret set. / 准备候选且只返回完整并绑定清单的已解码秘密集。 */
    public PreparedBackupSecrets prepareWithSecrets(Path archive, char[] backupPassword)
            throws IOException, BackupSecretException {
        PreparedMaterial prepared = null;
        BackupSecretDocument document = null;
        try {
            prepared = prepareValidated(archive);
            document = authenticate(prepared, backupPassword);
            return new PreparedBackupSecrets(candidate(prepared), document);
        } catch (IOException | BackupSecretException | RuntimeException exception) {
            if (document != null) document.close();
            if (prepared != null) cleanup(prepared.attempt(), exception);
            throw exception;
        } finally {
            if (backupPassword != null) Arrays.fill(backupPassword, '\0');
        }
    }

    /** Prepares complete schema-v5 material for one immediate product activation. / 为一次即时产品激活准备完整 schema-v5 材料。 */
    PreparedBackupActivation prepareForActivation(Path archive, char[] backupPassword)
            throws IOException, BackupSecretException {
        PreparedMaterial prepared = null;
        BackupSecretDocument document = null;
        try {
            prepared = prepareValidated(archive);
            if (!prepared.validation().manifest().supportsAutomaticActivation()) {
                throw BackupSecretException.create(BackupSecretFailureType.PAYLOAD_INVALID,
                        "the selected backup lacks schema-v5 automatic activation identities");
            }
            if (!prepared.validation().manifest().inventory().secretReferences().isEmpty()) {
                document = authenticate(prepared, backupPassword);
            }
            return new PreparedBackupActivation(prepared.validation(), prepared.candidate(), candidate(prepared),
                    java.util.Optional.ofNullable(document));
        } catch (IOException | BackupSecretException | RuntimeException exception) {
            if (document != null) document.close();
            if (prepared != null) cleanup(prepared.attempt(), exception);
            throw exception;
        } finally {
            if (backupPassword != null) Arrays.fill(backupPassword, '\0');
        }
    }

    private BackupSecretDocument authenticate(PreparedMaterial prepared, char[] backupPassword)
            throws IOException, BackupSecretException {
        byte[] envelope = null;
        try {
            BackupMember secretMember = prepared.validation().manifest().members().stream()
                    .filter(member -> member.kind() == BackupMemberKind.ENCRYPTED_SECRETS)
                    .findFirst().orElseThrow(() -> BackupSecretException.create(
                            BackupSecretFailureType.PAYLOAD_INVALID,
                            "the selected backup does not include encrypted secret revisions"));
            Path secretPath = prepared.candidate().root().resolve(secretMember.path()).normalize();
            if (!secretPath.startsWith(prepared.candidate().root())
                    || !Files.isRegularFile(secretPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(secretPath) != secretMember.size()
                    || secretMember.size() > BackupSecretCryptoService.MAXIMUM_ENVELOPE_BYTES) {
                throw BackupSecretException.create(BackupSecretFailureType.PAYLOAD_INVALID,
                        "the extracted encrypted secret member differs from the validated manifest");
            }
            envelope = Files.readAllBytes(secretPath);
            if (envelope.length != secretMember.size()) {
                throw BackupSecretException.create(BackupSecretFailureType.PAYLOAD_INVALID,
                        "the encrypted secret member changed after candidate extraction");
            }
            BackupSecretDocument document = secrets.decryptRevisions(backupPassword, envelope);
            try {
                requireManifestReferences(prepared.validation(), document);
                return document;
            } catch (BackupSecretException | RuntimeException exception) {
                document.close();
                throw exception;
            }
        } finally {
            if (envelope != null) Arrays.fill(envelope, (byte) 0);
        }
    }

    private PreparedMaterial prepareValidated(Path archive) throws IOException {
        BackupArchiveValidation validation = validator.validate(archive);
        WindowsRestoreAttempt attempt = workspace.createAttempt(
                validation.manifest().applicationId(), validation.archiveSha256());
        try {
            BackupRestoreCandidate candidate = extractor.extract(archive, attempt.candidateRoot(), validation);
            return new PreparedMaterial(validation, attempt, candidate);
        } catch (BackupException | RuntimeException exception) {
            cleanup(attempt, exception);
            throw exception;
        }
    }

    private static PreparedBackupCandidate candidate(PreparedMaterial prepared) {
        return new PreparedBackupCandidate(BackupArchiveInspection.from(prepared.validation()),
                prepared.candidate().root(), prepared.candidate().extractedBytes(), prepared.attempt());
    }

    private static void requireManifestReferences(
            BackupArchiveValidation validation,
            BackupSecretDocument document
    ) throws BackupSecretException {
        boolean matches;
        if (validation.manifest().supportsAutomaticActivation()) {
            Set<SecretReference> expected = new LinkedHashSet<>(
                    validation.manifest().inventory().secretReferences());
            Set<SecretReference> actual = document.revisions().stream().map(revision -> revision.reference())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            matches = !expected.isEmpty() && actual.equals(expected);
        } else {
            Set<String> expected = new LinkedHashSet<>(
                    validation.manifest().inventory().legacySecretReferences());
            Set<String> actual = document.revisions().stream().map(revision -> revision.reference().identifier())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            matches = !expected.isEmpty() && actual.equals(expected);
        }
        if (!matches) {
            throw BackupSecretException.create(BackupSecretFailureType.PAYLOAD_INVALID,
                    "authenticated secret revisions do not exactly match the manifest references");
        }
    }

    private void cleanup(WindowsRestoreAttempt attempt, Exception original) {
        try {
            workspace.discardAttempt(attempt);
        } catch (WindowsWorkspaceException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private record PreparedMaterial(
            BackupArchiveValidation validation,
            WindowsRestoreAttempt attempt,
            BackupRestoreCandidate candidate
    ) {
    }
}
