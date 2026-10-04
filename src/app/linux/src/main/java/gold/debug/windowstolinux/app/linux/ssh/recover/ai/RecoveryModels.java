package gold.debug.windowstolinux.app.linux.ssh.recover.ai;

import gold.debug.windowstolinux.app.linux.ssh.recover.model.TerminalObservation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Supplies transient observation and proposal results to the rescue controller. / 向救援控制器提供临时观测及建议结果。 */
public interface RecoveryModels {
    /** Observes text directly or interprets the selected terminal image. / 直接读取文字或解读所选终端图片。
     * @param observation original terminal evidence / 原终端证据
     * @return bounded classified result / 有界分类结果
     * @throws Exception if task authority, model invocation or cleanup fails / 任务授权、模型调用或清理失败时 */
    Result observe(TerminalObservation observation) throws Exception;

    /** Proposes a command without approving or executing it. / 建议命令，不批准或执行命令。
     * @param observation untrusted terminal facts / 不可信终端事实
     * @return bounded classified proposal / 有界分类建议
     * @throws Exception if task authority, model invocation or cleanup fails / 任务授权、模型调用或清理失败时 */
    Result decide(String observation) throws Exception;

    /** Retains only transient content and safe model diagnostics. / 仅保留临时内容及安全模型诊断。
     * @param code controller message code / 控制器消息码
     * @param content transient result, never a journal entry / 临时结果，不作日志条目
     * @param attempts safe model or platform classifications / 安全模型或平台分类 */
    record Result(String code, String content, Map<String, String> attempts) {
        /** Copies attempt order without persisting any model content. / 复制尝试顺序，不持久化任何模型内容。
         * @param code controller message code / 控制器消息码
         * @param content transient result / 临时结果
         * @param attempts safe classifications / 安全分类 */
        public Result {
            java.util.Objects.requireNonNull(code);
            java.util.Objects.requireNonNull(content);
            attempts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attempts));
        }

        /** Excludes raw terminal and model content from diagnostics. / 从诊断中排除原始终端及模型内容。
         * @return safe result description / 安全结果说明 */
        @Override
        public String toString() {
            return "RecoveryModelResult[" + code + "]";
        }
    }
}
