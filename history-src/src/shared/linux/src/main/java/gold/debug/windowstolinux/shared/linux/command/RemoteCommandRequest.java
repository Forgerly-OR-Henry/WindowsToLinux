package gold.debug.windowstolinux.shared.linux.command;

import java.time.Duration;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.agent.AgentAction;

/** Exact execution envelope, retained in memory only. / 仅保存在内存中的精确执行信封。
 * @param operation unique dispatch identity / 唯一派发身份
 * @param task owning task / 所属任务
 * @param target authenticated endpoint / 已认证端点
 * @param command complete submitted command / 完整提交命令
 * @param script complete script input, empty for data input / 完整脚本输入，数据输入时为空
 * @param inputDigest digest of exact stdin bytes / 精确标准输入字节摘要
 * @param directory execution working directory / 执行工作目录
 * @param identity execution identity description / 执行身份说明
 * @param timeout execution time limit / 执行时间限制
 * @param outputLimit maximum retained bytes / 最大保留字节
 * @param revision source and evidence revision / 源码及证据修订
 */
public record RemoteCommandRequest(String operation, String task, String target, String command, String script,
        String inputDigest, String directory, String identity, Duration timeout, long outputLimit, String revision) {
    /** Validates complete immutable execution fields. / 校验完整不可变执行字段。
     * @param operation dispatch identity / 派发身份
     * @param task task identity / 任务身份
     * @param target endpoint / 端点
     * @param command exact command / 精确命令
     * @param script exact script / 精确脚本
     * @param inputDigest stdin digest / 标准输入摘要
     * @param directory working directory / 工作目录
     * @param identity execution account / 执行账户
     * @param timeout time budget / 时间预算
     * @param outputLimit output budget / 输出预算
     * @param revision evidence revision / 证据修订
     */
    public RemoteCommandRequest {
        for (String value : new String[]{operation, task, target, directory, identity, revision})
            if (value == null || value.isBlank() || value.length() > 2048
                    || value.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid command identity");
        Objects.requireNonNull(command);
        Objects.requireNonNull(script);
        Objects.requireNonNull(timeout);
        if (command.isBlank() || command.length() + script.length() > 2097152 || command.indexOf('\0') >= 0
                || script.indexOf('\0') >= 0 || !inputDigest.matches("[0-9a-f]{64}") || timeout.isNegative()
                || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(7240)) > 0 || outputLimit < 1
                || outputLimit > 137438953472L)
            throw new IllegalArgumentException("invalid command bounds");
    }

    /** Binds content, endpoint, identity, limits and evidence together. / 同时绑定内容、端点、身份、限制及证据。
     * @return exact approval digest / 精确审批摘要
     */
    public String binding() {
        StringBuilder framed = new StringBuilder();
        for (String value : new String[]{operation, task, target, command, script, inputDigest, directory, identity,
                timeout.toString(), Long.toString(outputLimit), revision})
            framed.append(value.length()).append(':').append(value);
        return AgentAction.digest(framed.toString());
    }
}
