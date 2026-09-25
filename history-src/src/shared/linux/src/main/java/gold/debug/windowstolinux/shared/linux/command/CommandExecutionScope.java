package gold.debug.windowstolinux.shared.linux.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Worker-scoped mandatory boundary at the real SSH dispatch, including fixed tools. / 位于实际 SSH 派发处的工作线程强制边界，覆盖固定工具。 */
public final class CommandExecutionScope implements AutoCloseable {
    /** Worker-local execution policy. / 工作线程执行策略。 */
    private static final ThreadLocal<CommandExecutionScope> CURRENT = new ThreadLocal<>();

    /** Explicit sandbox execution environment, when the command uses one. / 命令使用沙箱时的显式执行环境。 */
    private static final ThreadLocal<List<String>> ENVIRONMENT = new ThreadLocal<>();
    /** Binds concrete directory and execution identity until this operation closes. / 在当前操作期间绑定具体目录及执行身份。
     * @param directory actual initial work directory / 实际初始工作目录
     * @param identity actual execution identity contract / 实际执行身份契约
     * @return scope restoring the preceding environment / 恢复先前环境的作用域
     */
    public static AutoCloseable environment(String directory, String identity) {
        var previous = ENVIRONMENT.get();
        ENVIRONMENT.set(List.of(Objects.requireNonNull(directory), Objects.requireNonNull(identity)));
        return () -> {
            if (previous == null)
                ENVIRONMENT.remove();
            else
                ENVIRONMENT.set(previous);
        };
    }
    /** Frozen task identity. / 冻结任务身份。 */
    private final String task;

    /** Frozen endpoint including account. / 含账户的冻结端点。 */
    private final String target;

    /** Current source and evidence revision. / 当前源码及证据修订。 */
    private final Supplier<String> revision;

    /** Review and persistence port. / 审核及持久化端口。 */
    private final RemoteCommandAudit audit;

    /** Installs one non-nestable command boundary. / 安装一个不可嵌套的命令边界。
     * @param task task identity / 任务身份
     * @param target exact endpoint / 精确端点
     * @param revision current evidence binding / 当前证据绑定
     * @param audit mandatory audit port / 强制审计端口
     */
    public CommandExecutionScope(String task, String target, Supplier<String> revision, RemoteCommandAudit audit) {
        if (CURRENT.get() != null)
            throw new IllegalStateException("command scope already active");
        this.task = Objects.requireNonNull(task);
        this.target = Objects.requireNonNull(target);
        this.revision = Objects.requireNonNull(revision);
        this.audit = Objects.requireNonNull(audit);
        CURRENT.set(this);
    }

    /** Validates target and captures the complete command before opening a channel. / 打开通道前校验目标并捕获完整命令。
     * @param target authenticated endpoint / 已认证端点
     * @param command submitted command / 提交命令
     * @param script script stdin when executable / 可执行标准输入脚本
     * @param input exact stdin bytes / 精确标准输入字节
     * @param timeout execution budget / 执行预算
     * @param limit output budget / 输出预算
     * @return audited dispatch or empty for non-AI operations / 已审计派发，非 AI 操作为空
     */
    public static Optional<Dispatch> before(String target, String command, String script, byte[] input,
            Duration timeout, long limit) {
        return beforeDigest(target, command, script, digest(input == null ? new byte[0] : input), timeout, limit);
    }

    /** Binds a separately verified data stream without persisting its body. / 绑定单独验证的数据流，不持久化正文。
     * @param target authenticated endpoint / 已认证端点
     * @param command complete command / 完整命令
     * @param script executable input if present / 存在时的可执行输入
     * @param inputDigest exact expected stream digest / 精确预期流摘要
     * @param timeout execution budget / 执行预算
     * @param limit output budget / 输出预算
     * @return audited dispatch / 已审计派发
     */
    public static Optional<Dispatch> beforeDigest(String target, String command, String script, String inputDigest,
            Duration timeout, long limit) {
        var scope = CURRENT.get();
        if (scope == null)
            return Optional.empty();
        if (!scope.target.equals(target))
            throw new SecurityException("command target differs from task target");
        var environment = ENVIRONMENT.get();
        var request = new RemoteCommandRequest(UUID.randomUUID().toString(), scope.task, target, command, script,
                inputDigest,
                environment == null
                        ? "authenticated login HOME; any cd is included in the exact script"
                        : environment.getFirst(),
                environment == null
                        ? "authenticated SSH account " + target
                                + "; explicit elevation is included in the exact command"
                        : environment.getLast(),
                timeout, limit, scope.revision.get());
        scope.audit.before(request);
        if (!scope.revision.get().equals(request.revision()))
            throw new SecurityException("command evidence changed after approval");
        scope.audit.dispatching(request);
        return Optional.of(new Dispatch(scope.audit, request));
    }

    /** Computes an exact byte digest without retaining payload. / 计算精确字节摘要，不保留载荷。
     * @param bytes payload / 载荷
     * @return SHA-256 digest / SHA-256 摘要
     */
    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Removes only the owner's worker scope. / 仅移除所属工作线程作用域。 */
    @Override
    public void close() {
        if (CURRENT.get() != this)
            throw new IllegalStateException("wrong command scope owner");
        CURRENT.remove();
    }

    /** Dispatch receipt binding result hooks to the exact approved request. / 将结果钩子绑定到精确已审批请求的派发回执。
     * @param audit durable review port / 持久化审核端口
     * @param request exact dispatched request / 精确派发请求
     */
    public record Dispatch(RemoteCommandAudit audit, RemoteCommandRequest request) {
        /** Reports the actual transport result. / 报告实际传输结果。
         * @param result returned result / 返回结果
         */
        public void completed(RemoteCommandResult result) {
            audit.after(request, result);
        }

        /** Reports a dispatch whose result cannot be established. / 报告无法确定结果的派发。 */
        public void unknown() {
            audit.unknown(request);
        }
    }
}
