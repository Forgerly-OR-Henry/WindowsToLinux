package gold.debug.windowstolinux.app.windows.workspace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One unforgeable platform-owned parent and digest-bound restore candidate path. / 单个不可伪造的平台持有父目录及摘要绑定恢复候选路径。
 */
public final class WindowsRestoreAttempt {
    /**
     * Parent.
     * <p>父级。
     */
    private final Path parent;
    /**
     * Candidate root.
     * <p>候选根目录。
     */
    private final Path candidateRoot;
    /**
     * Identity of the isolated deployment or restore candidate.
     * <p>隔离部署或恢复候选的身份。
     */
    private final String candidateId;
    /**
     * Parent file key.
     * <p>父级文件键。
     */
    private final Object parentFileKey;

    /**
     * Initializes windows restore attempt through its shared constructor contract.
     * <p>通过共享构造契约初始化Windows恢复尝试。
     *
     * @param parent parent / 父级
     * @param candidateRoot candidate root / 候选根目录
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     */
    WindowsRestoreAttempt(Path parent, Path candidateRoot, String candidateId) {
        this(parent, candidateRoot, candidateId, null);
    }

    /**
     * Validates and binds the inputs required by windows restore attempt.
     * <p>校验并绑定Windows恢复尝试所需输入。
     *
     * @param parent parent / 父级
     * @param candidateRoot candidate root / 候选根目录
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param parentFileKey parent file key / 父级文件键
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    WindowsRestoreAttempt(Path parent, Path candidateRoot, String candidateId, Object parentFileKey) {
        this.parent = Objects.requireNonNull(parent, "parent").toAbsolutePath().normalize();
        this.candidateRoot = Objects.requireNonNull(candidateRoot, "candidateRoot").toAbsolutePath().normalize();
        this.candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        this.parentFileKey = parentFileKey;
        if (!this.candidateId.matches("[a-z0-9][a-z0-9-]{0,79}")
                || !this.candidateRoot.getParent().equals(this.parent)
                || !this.candidateRoot.getFileName().toString().equals(this.candidateId)) {
            throw new IllegalArgumentException("restore attempt is outside its exact candidate parent");
        }
    }

    /**
     * Returns the private parent created for this attempt. / 返回为本次尝试创建的私有父目录。
     *
     * @return the private parent created for this attempt / 为本次尝试创建的私有父目录
     */
    public Path parent() {
        return parent;
    }

    /**
     * Returns the digest-bound candidate root. / 返回摘要绑定的候选根。
     *
     * @return the digest-bound candidate root / 摘要绑定的候选根
     */
    public Path candidateRoot() {
        return candidateRoot;
    }

    /**
     * Returns the digest-bound candidate identity. / 返回摘要绑定的候选身份。
     *
     * @return the digest-bound candidate identity / 摘要绑定的候选身份
     */
    public String candidateId() {
        return candidateId;
    }

    /**
     * Returns parent file key.
     * <p>返回父级文件键。
     *
     * @return parent file key / 父级文件键
     */
    Object parentFileKey() {
        return parentFileKey;
    }
}
