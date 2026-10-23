package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;

/** Resolves only unambiguous task-owned question scopes; compound shell actions retain exact bindings. / 仅解析明确的任务所属问题范围，复合Shell动作保留精确绑定。 */
final class DeploymentAgentQuestionScope {
    /** Canonical fixed-helper scalar fields produced by the owned renderer. / 所属渲染器产生的规范固定helper标量字段。 */
    private static final Pattern FIXED = Pattern.compile("^'[^']+' '([a-z-]+)' '([A-Za-z0-9._-]+)' '([A-Za-z0-9._-]+)' '[0-9a-f-]{36}'(?: '([^']*)')* *$");

    /** One literal recursive directory removal, without substitutions, globs or composition. / 单一字面量递归目录删除，不含替换、通配或组合。 */
    private static final Pattern REMOVE = Pattern.compile("^rm\\s+((?:(?:-[rRf]+|--recursive|--force|--)\\s+)+)(['\"]?)(?:\\./)?([A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*)\\2\\s*$");

    /** Prevents construction. / 禁止构造。 */
    private DeploymentAgentQuestionScope() {
    }

    /** Uses only the actual canonical helper request and complete input to resolve a question key. / 仅使用实际规范helper请求及完整输入解析问题键。
     * @param server actual original server / 实际原服务器
     * @param script actual fixed helper command / 实际固定helper命令
     * @param stdin actual complete input / 实际完整输入
     * @param exactBinding fallback exact command digest / 回退精确命令摘要
     * @return business-validated question meaning without weakening exact effect matching / 不放宽精确副作用匹配的经业务校验问题含义 */
    static ActionSemantics meaning(String server, String script, byte[] stdin, String exactBinding) {
        var fixed = FIXED.matcher(script);
        String kind = "deployment-agent", scope = exactBinding;
        if (fixed.matches()) {
            String verb = fixed.group(1), candidate = fixed.group(3);
            if (verb.equals("agent-run")) {
                var removal = REMOVE.matcher(new String(stdin, StandardCharsets.UTF_8).strip());
                if (removal.matches() && java.util.Arrays.stream(removal.group(1).strip().split("\\s+")).anyMatch(value -> value.equals("--recursive")
                    || value.startsWith("-") && !value.startsWith("--") && (value.contains("r") || value.contains("R")))) {
                    String path = removal.group(3);
                    if (java.util.Arrays.stream(path.split("/")).noneMatch(value -> value.equals(".") || value.equals(".."))) {
                        int separator = path.lastIndexOf('/');
                        kind = "deployment-agent-directory-delete";
                        scope = candidate + ":" + (separator < 0 ? "." : path.substring(0, separator));
                    }
                }
            } else if (verb.equals("agent-patch")) {
                try {
                    var patch = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stdin);
                    String path = patch.required("path").textValue();
                    if (path != null && path.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")
                        && java.util.Arrays.stream(path.split("/")).noneMatch(value -> value.equals(".") || value.equals(".."))) {
                        kind = "deployment-agent-source-patch";
                        scope = candidate + ":" + path;
                    }
                } catch (java.io.IOException | IllegalArgumentException unresolved) {
                    // Exact binding remains the only authority for an unresolved payload. / 未解析载荷仅以精确绑定授权。
                }
            }
        }
        return new ActionSemantics("deployment.agent.helper.v1", Set.of(new ActionTarget(server, kind, scope)));
    }
}
