package gold.debug.windowstolinux.mod.deployment.ai;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;

/** Adds complete executable input while keeping protected data payloads opaque. / 增补完整可执行输入，受保护数据载荷继续保持不透明。 */
final class DeploymentCommandEvidence {
    /** Fixed operations whose stdin is executable Bash rather than protected data. / stdin是可执行Bash而非受保护数据的固定动作。 */
    private static final Set<String> SCRIPT_INPUT = Set.of("deployment.helper.install.v1",
        "deployment.helper.install.tracked.v1", "deployment.helper.install.status.v1", "deployment.build.run.v1");

    /** Prevents construction. / 禁止构造。 */
    private DeploymentCommandEvidence() {
    }

    /** Classifies only original executable formats; arbitrary payloads are never decoded. / 仅分类原可执行格式，绝不解码任意载荷。
     * @param intent exact fixed command intent / 精确固定命令意图
     * @param request complete immutable request / 完整不可变请求
     * @param policy original policy / 原策略
     * @return bounded complete command facts with opaque protected input binding / 有界完整命令事实及不透明受保护输入绑定
     * @throws CharacterCodingException if executable input is not valid UTF-8 / 可执行输入不是有效UTF-8时 */
    static Map<String, Object> facts(ActionIntent intent, CommandRequest request, String policy) throws CharacterCodingException {
        var facts = new LinkedHashMap<String, Object>();
        String operation = intent.semantics().orElseThrow().operation();
        facts.put("policy", policy);
        facts.put("operation", operation);
        facts.put("targets", intent.serverIds());
        facts.put("businessRevision", intent.revision());
        facts.put("exactAction", intent.exactAction());
        boolean agentRun = operation.equals("deployment.agent.helper.v1")
            && request.script().startsWith("'" + DeploymentManagedHelperBundle.PATH + "' 'agent-run' ");
        boolean patch = operation.equals("deployment.agent.helper.v1")
            && request.script().startsWith("'" + DeploymentManagedHelperBundle.PATH + "' 'agent-patch' ");
        byte[] input = request.stdin();
        try {
            if (SCRIPT_INPUT.contains(operation) || agentRun || patch) {
                String executable = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
                if (!patch && !executable.isBlank()) {
                    if (agentRun) DeploymentCommandStaticGate.requireAllowed(executable);
                    else DeploymentCommandStaticGate.requireFixedAllowed(executable);
                }
                facts.put("inputKind", patch ? "SOURCE_PATCH" : "EXECUTABLE_SCRIPT");
                facts.put("completeExecutableInput", executable);
            } else {
                facts.put("inputKind", input.length == 0 ? "EMPTY" : "OPAQUE_BOUND_DATA");
            }
        } finally {
            Arrays.fill(input, (byte) 0);
        }
        return Map.copyOf(facts);
    }
}
