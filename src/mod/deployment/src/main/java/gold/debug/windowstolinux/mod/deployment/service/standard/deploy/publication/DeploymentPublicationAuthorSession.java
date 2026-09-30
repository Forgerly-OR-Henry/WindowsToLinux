package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.util.Map;
import java.util.Objects;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;

/** Owns one physical author session at a time across explicitly reviewed operations. / 跨明确审阅操作一次拥有一个物理作者会话。 */
final class DeploymentPublicationAuthorSession implements AutoCloseable {
    /** Current physically owned connection or absence after its close. / 当前物理所属连接或关闭后的空值。 */
    private RemoteSession session;
    /** Original child occupancies. / 原子占用。 */
    private final Map<String, DeploymentPublicationCleanupJournal> journals;
    /** Optional whole graph occupancy. / 可选整图占用。 */
    private final DeploymentGraphPublicationJournal root;
    /** Takes ownership of the actual connection. / 取得实际连接所有权。
     * @param session actual connection / 实际连接
     * @param journals original child occupancies / 原子占用
     * @param root original whole graph or absence / 原整图或空值 */
    DeploymentPublicationAuthorSession(RemoteSession session, Map<String, DeploymentPublicationCleanupJournal> journals,
            DeploymentGraphPublicationJournal root) {
        this.session = Objects.requireNonNull(session); this.journals = Map.copyOf(journals); this.root = root;
    }
    /** Returns the actual current physical connection. / 返回实际当前物理连接。
     * @return actual owned session / 实际所属会话 */
    RemoteSession current() { return Objects.requireNonNull(session); }
    /** Takes only the formally confirmed next connection after old physical close. / 旧物理关闭后仅取得正式确认下一连接。
     * @param next actual confirmed next session / 实际已确认下一会话 */
    void next(RemoteSession next) { if (session != null) throw new IllegalStateException("Previous author session remains open"); session = Objects.requireNonNull(next); }
    /** Physically closes before recording child and root closure; no new effect is dispatched. / 记录子及根关闭前物理关闭，不发送新副作用。
     * @return whether every exact checkpoint recorded actual closure / 每个精确检查点是否记录实际关闭
     * @throws Exception if actual close is unavailable / 实际关闭不可用时 */
    boolean retire() throws Exception {
        close(); boolean all = true;
        for (var journal : journals.values()) all &= journal.sessionClosed();
        return all && (root == null || root.mark("SESSION_CLOSED"));
    }
    /** Closes only the current physically owned connection once. / 仅关闭当前物理所属连接一次。
     * @throws Exception if physical release is unconfirmed / 物理释放未确认时 */
    @Override public void close() throws Exception {
        if (session != null) { session.close(); session = null; }
    }
}
