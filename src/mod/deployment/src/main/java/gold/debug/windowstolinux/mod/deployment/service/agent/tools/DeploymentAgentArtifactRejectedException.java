package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.*;

/** A complete, bound helper rejection after output freeze, without a sealed artifact. / 输出冻结后完整且绑定的 helper 拒绝，未生成封存制品。 */
public final class DeploymentAgentArtifactRejectedException extends Exception {
    private static final String PREFIX = "ARTIFACT_REJECT=";

    private static final Set<String> CODES = Set.of("INVALID_PATH", "SYMBOLIC_LINK", "SPECIAL_FILE", "HARD_LINK", "MEMBER_LIMIT", "FILE_LIMIT", "EMPTY_TREE");

    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final UUID task;

    private final String candidate;

    private final String revision;

    private final String code;

    private final String path;

    private DeploymentAgentArtifactRejectedException(UUID task, String candidate, String revision, String code, String path) {
        super("Artifact output rejected: " + code);
        this.task = task;
        this.candidate = candidate;
        this.revision = revision;
        this.code = code;
        this.path = path;
    }

    /** Accepts only the fixed seal operation's complete exclusive rejection frame. / 仅接受固定封存操作完整且独占的拒绝帧。 */
    static Optional<DeploymentAgentArtifactRejectedException> receipt(OperationResult<CommandResult> result, UUID task,
                                                                      String candidate, String revision) {
        var output = result.value().orElse(null);
        if (result.outcome() != TaskResult.FAILED || output == null || output.truncated()
            || !output.exitCode().equals(Optional.of(65)) || !output.stderr().isEmpty()
            || result.problem().filter(problem -> problem.code() == ProblemCode.IO_FAILURE && problem.eventCode().equals("SSH_COMMAND_NONZERO")
            && "COMPLETED".equals(problem.context().get("inputState")) && "true".equals(problem.context().get("channelClosed"))
            && "true".equals(problem.context().get("exitObserved"))).isEmpty()) return Optional.empty();
        String text = output.stdout();
        if (!text.startsWith(PREFIX) || !text.endsWith("\n") || text.length() > 4096 || text.indexOf('\n') != text.length() - 1)
            return Optional.empty();
        try {
            var node = JSON.readTree(text.substring(PREFIX.length(), text.length() - 1));
            var fields = new HashSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!node.isObject() || !fields.equals(Set.of("task", "candidate", "sourceRevision", "code", "path"))
                || fields.stream().anyMatch(field -> !node.path(field).isTextual())
                || !task.toString().equals(node.path("task").textValue()) || !candidate.equals(node.path("candidate").textValue())
                || !revision.equals(node.path("sourceRevision").textValue())) return Optional.empty();
            String code = node.path("code").textValue(), path = node.path("path").textValue();
            if (!CODES.contains(code) || path.length() > 500 || path.contains("\\") || path.contains(":")
                || path.chars().anyMatch(c -> c < 32)
                || !path.isEmpty() && Arrays.stream(path.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
                return Optional.empty();
            if (Set.of("SYMBOLIC_LINK", "SPECIAL_FILE", "HARD_LINK").contains(code) && path.isEmpty())
                return Optional.empty();
            return Optional.of(new DeploymentAgentArtifactRejectedException(task, candidate, revision, code, path));
        } catch (java.io.IOException | RuntimeException invalid) {
            return Optional.empty();
        }
    }

    public boolean matches(UUID task, String candidate, String revision) {
        return this.task.equals(task) && this.candidate.equals(candidate) && this.revision.equals(revision);
    }

    /** This is executed rejection evidence, never authorization or a source READ. / 这是已执行的拒绝依据，不是授权或来源 READ。 */
    public String feedback() throws java.io.IOException {
        return JSON.writeValueAsString(Map.of("code", code, "artifactRelativePath", path, "sealed", false,
            "outputFrozen", true, "meaning", "The helper stopped the build and verified the current source and frozen output. The entire output tree is not a portable artifact. Correct output using a separately reviewed command, inspect, then seal again. No artifact was recorded by this attempt."));
    }
}
