package gold.debug.windowstolinux.shared.linux.sshd.workspace;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.shared.linux.command.*;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.*;
import gold.debug.windowstolinux.shared.linux.workspace.*;

/** Mechanical helper adapter for protected source and sandboxed scripts. / 受保护源码及沙箱脚本的机械 helper 适配器。 */
public final class SshdRemoteProjectPort implements RemoteProjectPort {
    /** Authenticated, audited executor. / 已认证且经过审计的执行器。 */
    private final SshCommandExecutor commands;

    /** Fixed backend captured when preparing each candidate. / 准备每个候选时捕获的固定后端。 */
    private final Map<String, String> engines = new HashMap<>();

    /** Exact task captured for prepared candidates. / 已准备候选捕获的精确任务。 */
    private final Map<String, String> tasks = new HashMap<>();

    /** Structured patch serializer. / 结构化补丁序列化器。 */
    private final ObjectMapper json = new ObjectMapper();
    /** Binds one authenticated transport. / 绑定一个已认证传输。
     * @param commands audited transport / 已审计传输
     */
    public SshdRemoteProjectPort(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands);
    }

    /** Opens the default native sandbox. / 打开默认原生沙箱。
     * @param task owning task / 所属任务
     * @param workspace uploaded source / 已上传源码
     * @return actual source revision / 实际源码修订
     * @throws Exception when preparation fails / 准备失败时
     */
    @Override
    public String open(String task, RemoteWorkspace workspace) throws Exception {
        return open(task, workspace, "ordinary");
    }

    /** Freezes a backend selected by the managed delivery contract. / 冻结受管交付契约选择的后端。
     * @param task task identity / 任务身份
     * @param workspace uploaded candidate / 上传候选
     * @param engine ordinary, docker or podman / ordinary、docker 或 podman
     * @return remote source revision / 远端源码修订
     * @throws Exception when source preparation is rejected / 源码准备被拒绝时
     */
    @Override
    public String open(String task, RemoteWorkspace workspace, String engine) throws Exception {
        DeploymentRemoteTaskScope.requireTask(task);
        if (!Set.of("ordinary", "docker", "podman").contains(engine))
            throw new IllegalArgumentException("sandbox engine");
        if (tasks.containsKey(workspace.candidateId()))
            throw new IllegalStateException("candidate already opened");
        var result = commands.execProtocol(render("agent-open", workspace, task, workspace.sourceSha256()),
                Duration.ofMinutes(2), true);
        String revision = value(result, "SOURCE_REVISION");
        engines.put(workspace.candidateId(), engine);
        tasks.put(workspace.candidateId(), task);
        return revision;
    }

    /** Reads current protected source evidence. / 读取当前受保护源码证据。
     * @param task task identity / 任务身份
     * @param workspace candidate / 候选
     * @param revision source revision / 源码修订
     * @param path relative file / 相对文件
     * @param offset line offset / 行偏移
     * @param limit line count / 行数
     * @return bounded redacted text and digest / 有界脱敏文本及摘要
     * @throws Exception on invalid evidence / 证据无效时
     */
    @Override
    public String read(String task, RemoteWorkspace workspace, String revision, String path, int offset, int limit)
            throws Exception {
        scope(task, workspace, revision);
        gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand.relative(path, false);
        if (offset < 0 || offset > 1000000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("source read page");
        var result = commands.execProtocol(render("agent-read", workspace, task, revision, path,
                Integer.toString(offset), Integer.toString(limit)), Duration.ofSeconds(30), true);
        require(result);
        return result.output();
    }

    /** Executes exactly the reviewed script under the helper sandbox. / 在 helper 沙箱下执行精确已审核脚本。
     * @param task task identity / 任务身份
     * @param workspace candidate / 候选
     * @param revision source revision / 源码修订
     * @param script exact model script / 精确模型脚本
     * @param timeout execution budget / 执行预算
     * @param outputLimit output budget / 输出预算
     * @return real process result / 实际进程结果
     * @throws Exception on invalid or unavailable execution / 执行无效或不可用时
     */
    @Override
    public RemoteCommandResult command(String task, RemoteWorkspace workspace, String revision, String script,
            Duration timeout, long outputLimit) throws Exception {
        scope(task, workspace, revision);
        if (script.isBlank() || script.length() > 24000 || script.indexOf('\0') >= 0 || timeout.toSeconds() < 1
                || timeout.toSeconds() > 7200 || outputLimit < 4096 || outputLimit > 1048576)
            throw new IllegalArgumentException("sandbox command limits");
        String command = render("agent-run", workspace, task, revision, Long.toString(timeout.toSeconds()),
                Long.toString(outputLimit), engines.get(workspace.candidateId()));
        try (var environment = CommandExecutionScope.environment(workspace.candidateRoot() + "/mutable/source",
                "unprivileged task build identity; helper-enforced read-only source; backend="
                        + engines.get(workspace.candidateId()))) {
            return commands.execProtocolWithInput(command, script.getBytes(StandardCharsets.UTF_8),
                    timeout.plusSeconds(20), outputLimit);
        }
    }

    /** Sends a structured patch as data, never interpolated shell. / 将结构化补丁作为数据发送，绝不拼接为 Shell。
     * @param task task identity / 任务身份
     * @param workspace candidate / 候选
     * @param patch exact reviewed diff / 精确已审核差异
     * @param approval bound review identity / 绑定审核身份
     * @return actual revised source digest / 实际更新源码摘要
     * @throws Exception on conflict or unknown execution / 冲突或执行未知时
     */
    @Override
    public String patch(String task, RemoteWorkspace workspace, RemoteSourcePatch patch, String approval)
            throws Exception {
        scope(task, workspace, patch.sourceRevision());
        if (!patch.binding().equals(approval))
            throw new SecurityException("patch approval binding mismatch");
        byte[] bytes = json.writeValueAsBytes(patch);
        try {
            if (bytes.length > 65536)
                throw new IllegalArgumentException("patch wire budget");
            var result = commands.execProtocolWithInput(
                    render("agent-patch", workspace, task, patch.sourceRevision(), approval), bytes,
                    Duration.ofSeconds(30));
            if (result.known() && !result.succeeded()) {
                // A failed write is rejected only after proving the old revision still exists. / 写入失败后，仅在证明旧修订仍存在时才判定为已拒绝。
                try {
                    read(task, workspace, patch.sourceRevision(), patch.path(), 0, 1);
                } catch (Exception unverified) {
                    throw new IllegalStateException("source patch outcome unknown", unverified);
                }
                throw new IllegalArgumentException("patch rejected; unchanged source revision verified");
            }
            return value(result, "SOURCE_REVISION");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Proves output sealing and source provenance. / 证明输出封存及源码来源。
     * @param task task identity / 任务身份
     * @param workspace candidate / 候选
     * @param revision source revision / 源码修订
     * @return output digest / 输出摘要
     * @throws Exception when output is not verifiable / 输出无法验证时
     */
    @Override
    public String seal(String task, RemoteWorkspace workspace, String revision) throws Exception {
        scope(task, workspace, revision);
        return value(
                commands.execProtocol(render("agent-seal", workspace, task, revision), Duration.ofMinutes(2), true),
                "DIGEST");
    }

    /** Checks the exact task before every remote operation. / 每个远端操作前检查精确任务。
     * @param task supplied task / 传入任务
     * @param workspace candidate / 候选
     * @param revision supplied source digest / 传入源码摘要
     */
    private void scope(String task, RemoteWorkspace workspace, String revision) {
        if (!task.equals(tasks.get(workspace.candidateId())) || !revision.matches("[0-9a-f]{64}"))
            throw new SecurityException("unprepared or foreign source workspace");
    }

    /** Renders only fixed helper verbs and quoted scalar arguments. / 仅渲染固定 helper 动词及已引用标量参数。
     * @param verb implementation-owned helper verb / 实现持有的 helper 动词
     * @param workspace task candidate / 任务候选
     * @param task exact task identity / 精确任务身份
     * @param arguments bounded typed arguments / 有界类型化参数
     * @return complete command / 完整命令
     */
    private static String render(String verb, RemoteWorkspace workspace, String task, String... arguments) {
        var values = new ArrayList<>(
                List.of(ManagedHelperProtocol.PATH, verb, workspace.applicationId(), workspace.candidateId(), task));
        values.addAll(List.of(arguments));
        return values.stream().map(CommandText::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    /** Requires known completion before accepting protocol values. / 接受协议值前要求已知完成。
     * @param result transport result / 传输结果
     */
    private static void require(RemoteCommandResult result) {
        if (!result.known())
            throw new IllegalStateException("remote source result unknown");
        if (!result.succeeded())
            throw new IllegalArgumentException("remote source operation rejected: " + result.failureEvidence());
    }

    /** Extracts a verified SHA-256 identity. / 提取已验证 SHA-256 身份。
     * @param result actual protocol response / 实际协议响应
     * @param key required field / 必需字段
     * @return exact digest / 精确摘要
     */
    private static String value(RemoteCommandResult result, String key) {
        require(result);
        String value = CommandText.lines(result.output()).get(key);
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("missing source protocol evidence");
        return value;
    }
}
