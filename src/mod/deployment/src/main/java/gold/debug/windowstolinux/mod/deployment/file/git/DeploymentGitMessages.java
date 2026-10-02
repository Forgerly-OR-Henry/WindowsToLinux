package gold.debug.windowstolinux.mod.deployment.file.git;

import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.ProcessMessage;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;

/** Exact business messages and exit receipts for one Git preparation request. / 一次 Git 准备请求的精确业务消息及退出凭据。 */
public final class DeploymentGitMessages {
    /** Versioned author-local request type. / 版本化作者内部请求类型。 */
    static final String TYPE = "deployment.git.prepare.v1";

    /** Prevents utility construction. / 禁止构造工具实例。 */
    private DeploymentGitMessages() {
    }

    /** Encodes an explicit credential-free request. / 编码明确的不含凭据请求。
     * @param request original selection / 原选择
     * @return exact business JSON / 精确业务 JSON */
    public static String encode(DeploymentGitRequest request) {
        String kind = switch (request.reference()) {
            case DeploymentGitReference.DefaultBranch ignored -> "DEFAULT";
            case DeploymentGitReference.Branch ignored -> "BRANCH";
            case DeploymentGitReference.Tag ignored -> "TAG";
            case DeploymentGitReference.Commit ignored -> "COMMIT";
        };
        var node = DeploymentGitProtocol.empty().put("remote", request.remote().location().toString()).put("kind", kind)
            .put("reference", request.reference().value()).put("maximumArchiveBytes", request.maximumArchiveBytes())
            .put("permitLocalFileRemote", request.permitLocalFileRemote());
        var hosts = node.putArray("allowedHosts");
        request.allowedHosts().stream().sorted().forEach(hosts::add);
        return node.toString();
    }

    /** Decodes the exact request without accepting unknown fields or type coercion. / 解码精确请求，不接受未知字段或类型强转。
     * @param text bounded protocol JSON / 有界协议 JSON
     * @return validated request / 已验证请求
     * @throws IOException if the request is malformed / 请求无效时 */
    public static DeploymentGitRequest decode(String text) throws IOException {
        try {
            var node = DeploymentGitProtocol.object(text);
            DeploymentGitProtocol.fields(node, Set.of("remote", "kind", "reference", "allowedHosts",
                "maximumArchiveBytes", "permitLocalFileRemote"));
            String value = DeploymentGitProtocol.text(node, "reference");
            DeploymentGitReference reference = switch (DeploymentGitProtocol.text(node, "kind")) {
                case "DEFAULT" -> {
                    if (!value.equals("HEAD"))
                        throw new IllegalArgumentException();
                    yield new DeploymentGitReference.DefaultBranch();
                }
                case "BRANCH" -> new DeploymentGitReference.Branch(value);
                case "TAG" -> new DeploymentGitReference.Tag(value);
                case "COMMIT" -> new DeploymentGitReference.Commit(value);
                default -> throw new IllegalArgumentException();
            };
            if (!node.get("allowedHosts").isArray() || node.get("allowedHosts").size() > 1024
                || !node.get("maximumArchiveBytes").isIntegralNumber()
                || !node.get("maximumArchiveBytes").canConvertToLong()
                || !node.get("permitLocalFileRemote").isBoolean())
                throw new IllegalArgumentException();
            var hosts = new HashSet<String>();
            for (var host : node.get("allowedHosts"))
                if (!host.isTextual() || !hosts.add(host.textValue()))
                    throw new IllegalArgumentException();
            return new DeploymentGitRequest(
                new DeploymentGitRemote(URI.create(DeploymentGitProtocol.text(node, "remote"))), reference, hosts,
                node.get("maximumArchiveBytes").longValue(), node.get("permitLocalFileRemote").booleanValue());
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid Git worker request");
        }
    }

    /** Encodes a business result only after native processes and pipes returned. / 仅在原生进程及管道返回后编码业务结果。
     * @param job original operation identity / 原操作身份
     * @param commit resolved commit on success / 成功时解析的提交
     * @param problem optional safe failure / 可选安全失败
     * @return exact result envelope / 精确结果封套 */
    static String result(UUID job, String commit, Problem problem) {
        var root = DeploymentGitProtocol.empty();
        root.put("outcome",
            problem == null
                ? "SUCCEEDED"
                : problem.code() == ProblemCode.UNKNOWN
                ? "UNKNOWN"
                : problem.code() == ProblemCode.CANCELLED ? "CANCELLED" : "FAILED");
        var value = root.putObject("value").put("jobId", job.toString()).put("processesClosed",
            problem == null || problem.code() != ProblemCode.UNKNOWN);
        if (commit == null)
            value.putNull("commit");
        else
            value.put("commit", commit);
        if (problem == null)
            root.putNull("problem");
        else {
            root.putObject("problem").put("code", problem.code().name()).put("eventCode", problem.eventCode())
                .put("message", problem.message()).putNull("diagnosticId").putObject("context");
        }
        return root.toString();
    }

    /** Requires original operation ownership and positive physical cleanup evidence. / 要求原操作归属及肯定物理清理证据。
     * @param message original business value / 原业务值
     * @param job expected original operation / 预期原操作
     * @return optional successful commit / 可选成功提交
     * @throws IOException if evidence is missing, conflicting or unknown / 证据缺失、冲突或未知时 */
    static Optional<String> receipt(ProcessMessage message, UUID job) throws IOException {
        try {
            if (!message.type().equals(TYPE))
                throw new IllegalArgumentException();
            JsonNode node = DeploymentGitProtocol.object(message.jsonObject());
            DeploymentGitProtocol.fields(node, Set.of("jobId", "commit", "processesClosed"));
            if (!DeploymentGitProtocol.uuid(node, "jobId").equals(job) || !node.get("processesClosed").isBoolean()
                || !node.get("processesClosed").booleanValue())
                throw new IllegalArgumentException();
            if (node.get("commit").isNull())
                return Optional.empty();
            String commit = DeploymentGitProtocol.text(node, "commit");
            if (!commit.matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException();
            return Optional.of(commit);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Git cleanup receipt is unconfirmed");
        }
    }
}
