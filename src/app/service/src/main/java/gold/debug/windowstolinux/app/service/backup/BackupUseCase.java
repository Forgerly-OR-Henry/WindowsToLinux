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

/**
 * Local inspection and isolated extraction use case that performs no remote mutation. / 不执行远端修改的本地检查与隔离提取用例。
 */
public final class BackupUseCase {
    /**
     * Validator.
     * <p>校验器。
     */
    private final BackupArchiveValidator validator;
    /**
     * Extractor.
     * <p>提取器。
     */
    private final BackupArchiveExtractor extractor;
    /**
     * Platform-owned work area with enforced path boundaries.
     * <p>具有路径边界约束的平台工作区。
     */
    private final WindowsRestoreWorkspace workspace;
    /**
     * Bound backup secret crypto service collaborator for credential references or scoped secret-access service.
     * <p>处理凭据引用或限定作用域的秘密访问服务的备份秘密加密服务协作对象。
     */
    private final BackupSecretCryptoService secrets;

    /**
     * Creates the desktop backup use case over the platform-owned workspace. / 基于平台持有工作区创建桌面备份用例。
     *
     * @param workDirectory work directory / 工作目录
     */
    public BackupUseCase(Path workDirectory) {
        this(new BackupArchiveValidator(BackupArchivePolicy.defaults()), new BackupArchiveExtractor(),
                new WindowsRestoreWorkspace(workDirectory), new BackupSecretCryptoService());
    }

    /**
     * Validates and binds the inputs required by backup use case.
     * <p>校验并绑定备份用例所需输入。
     *
     * @param validator validator / 校验器
     * @param extractor extractor / 提取器
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Fully validates one selected archive without extracting or connecting. / 完整校验一个已选归档且不提取、不连接。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @return constructed or resolved backup archive inspection / 构造或解析得到的备份归档检查
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    public BackupArchiveInspection inspect(Path archive) throws BackupException {
        return BackupArchiveInspection.from(validator.validate(archive));
    }

    /**
     * Revalidates and extracts one new local candidate without activating it. / 重新校验并提取一个新的本地候选且不激活。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @return constructed or resolved prepared backup candidate / 构造或解析得到的已准备备份候选
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public PreparedBackupCandidate prepare(Path archive) throws IOException {
        return candidate(prepareValidated(archive));
    }

    /**
     * Deletes only the exact platform-owned attempt containing this prepared candidate. / 仅删除包含此已准备候选的精确平台持有尝试。
     *
     * @param candidate candidate / 候选
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Prepares a candidate and returns only a complete manifest-bound decoded secret set. / 准备候选且只返回完整并绑定清单的已解码秘密集。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @return constructed or resolved prepared backup secrets / 构造或解析得到的已准备备份秘密集合
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
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

    /**
     * Prepares complete schema-v5 material for one immediate product activation. / 为一次即时产品激活准备完整 schema-v5 材料。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @return constructed or resolved prepared backup activation / 构造或解析得到的已准备备份激活
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
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

    /**
     * Reads and authenticates the archive's encrypted secret envelope using the supplied backup password.
     * <p>使用所提供备份密码读取并认证归档内的加密秘密信封。
     *
     * @param prepared prepared / 已准备
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @return and authenticates the archive's encrypted secret envelope using the supplied backup password / 使用所提供备份密码读取并认证归档内的加密秘密信封
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
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

    /**
     * Prepares validated.
     * <p>准备已验证。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @return constructed or resolved prepared material / 构造或解析得到的已准备素材
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
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

    /**
     * Builds prepared backup candidate from the supplied candidate inputs.
     * <p>根据所提供候选输入构建已准备备份候选。
     *
     * @param prepared prepared / 已准备
     * @return prepared backup candidate from the supplied candidate inputs / 根据所提供候选输入构建已准备备份候选
     */
    private static PreparedBackupCandidate candidate(PreparedMaterial prepared) {
        return new PreparedBackupCandidate(BackupArchiveInspection.from(prepared.validation()),
                prepared.candidate().root(), prepared.candidate().extractedBytes(), prepared.attempt());
    }

    /**
     * Requires manifest references.
     * <p>要求清单引用集合。
     *
     * @param validation validation / 校验
     * @param document document / 文档
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
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

    /**
     * Cleans up backup.
     * <p>清理备份。
     *
     * @param attempt attempt / 尝试
     * @param original original / 原始
     */
    private void cleanup(WindowsRestoreAttempt attempt, Exception original) {
        try {
            workspace.discardAttempt(attempt);
        } catch (WindowsWorkspaceException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Associates verified backup input with the local material required for activation.
     * <p>将已验证备份输入与激活所需的本地素材关联。
     *
     * @param validation validation / 校验
     * @param attempt attempt / 尝试
     * @param candidate candidate / 候选
     */
    private record PreparedMaterial(
            BackupArchiveValidation validation,
            WindowsRestoreAttempt attempt,
            BackupRestoreCandidate candidate
    ) {
    }
}
