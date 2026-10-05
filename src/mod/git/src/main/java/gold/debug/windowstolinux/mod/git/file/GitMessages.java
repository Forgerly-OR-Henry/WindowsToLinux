package gold.debug.windowstolinux.mod.git.file;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.task.process.ProcessMessage;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.mod.git.api.GitReferenceKind;
import gold.debug.windowstolinux.mod.git.api.GitSourceRequest;

/** Exact business messages and exit receipts for one Git preparation request. / 一次 Git 准备请求的精确业务消息及退出凭据。 */
public final class GitMessages {
    /** Versioned author-local request type. / 版本化作者内部请求类型。 */
    static final String TYPE = "git.source.prepare.v1";

    /** Prevents utility construction. / 禁止构造工具实例。 */
    private GitMessages() {
    }

    /** Validates public intent before retaining or executing worker work. / 保留或执行 worker 工作前校验公开意图。
     * @param source original public intent / 原公开意图
     * @return strictly validated private transport input / 严格校验的私有传输输入 */
    public static GitRequest request(GitSourceRequest source) {
        GitRef reference = switch (source.referenceKind()) {
            case DEFAULT_BRANCH -> {
                if (!source.reference().equals("HEAD")) throw new IllegalArgumentException("Invalid default reference");
                yield new GitRef.DefaultBranch();
            }
            case BRANCH -> new GitRef.Branch(source.reference());
            case TAG -> new GitRef.Tag(source.reference());
            case COMMIT -> new GitRef.Commit(source.reference());
        };
        return new GitRequest(GitRemote.parse(source.remote()), reference, source.allowedHosts(),
            source.permitLocalFileRemote());
    }

    /** Restores public intent from strictly decoded original worker fields. / 从严格解码的原 worker 字段恢复公开意图。
     * @param operation original operation / 原操作
     * @param material original material / 原素材
     * @param executable original tool text / 原工具文本
     * @param request checked original transport request / 已核验原传输请求
     * @return original immutable public intent / 原不可变公开意图 */
    public static GitSourceRequest source(UUID operation, UUID material, String executable, GitRequest request) {
        var kind = switch (request.reference()) {
            case GitRef.DefaultBranch ignored -> GitReferenceKind.DEFAULT_BRANCH;
            case GitRef.Branch ignored -> GitReferenceKind.BRANCH;
            case GitRef.Tag ignored -> GitReferenceKind.TAG;
            case GitRef.Commit ignored -> GitReferenceKind.COMMIT;
        };
        return new GitSourceRequest(operation, material, request.remote().location().toString(), kind,
            request.reference().value(), request.allowedHosts(), request.permitLocalFileRemote(), executable);
    }

    /** Encodes an explicit credential-free request. / 编码明确的不含凭据请求。
     * @param request original selection / 原选择
     * @return exact business JSON / 精确业务 JSON */
    public static String encode(GitRequest request) {
        String kind = switch (request.reference()) {
            case GitRef.DefaultBranch ignored -> "DEFAULT";
            case GitRef.Branch ignored -> "BRANCH";
            case GitRef.Tag ignored -> "TAG";
            case GitRef.Commit ignored -> "COMMIT";
        };
        var node = GitProtocol.empty().put("remote", request.remote().location().toString()).put("kind", kind)
            .put("reference", request.reference().value())
            .put("permitLocalFileRemote", request.permitLocalFileRemote());
        var hosts = node.putArray("allowedHosts");
        request.allowedHosts().stream().sorted().forEach(hosts::add);
        return node.toString();
    }

    /** Decodes the exact request without accepting unknown fields or type coercion. / 解码精确请求，不接受未知字段或类型强转。
     * @param text bounded protocol JSON / 有界协议 JSON
     * @return validated request / 已验证请求
     * @throws IOException if the request is malformed / 请求无效时 */
    public static GitRequest decode(String text) throws IOException {
        try {
            var node = GitProtocol.object(text);
            GitProtocol.fields(node, Set.of("remote", "kind", "reference", "allowedHosts", "permitLocalFileRemote"));
            String value = GitProtocol.text(node, "reference");
            GitRef reference = switch (GitProtocol.text(node, "kind")) {
                case "DEFAULT" -> {
                    if (!value.equals("HEAD"))
                        throw new IllegalArgumentException();
                    yield new GitRef.DefaultBranch();
                }
                case "BRANCH" -> new GitRef.Branch(value);
                case "TAG" -> new GitRef.Tag(value);
                case "COMMIT" -> new GitRef.Commit(value);
                default -> throw new IllegalArgumentException();
            };
            if (!node.get("allowedHosts").isArray() || node.get("allowedHosts").size() > 1024
                || !node.get("permitLocalFileRemote").isBoolean())
                throw new IllegalArgumentException();
            var hosts = new HashSet<String>();
            for (var host : node.get("allowedHosts"))
                if (!host.isTextual() || !hosts.add(host.textValue()))
                    throw new IllegalArgumentException();
            return new GitRequest(
                new GitRemote(URI.create(GitProtocol.text(node, "remote"))), reference, hosts,
                node.get("permitLocalFileRemote").booleanValue());
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
        var root = GitProtocol.empty();
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
            JsonNode node = GitProtocol.object(message.jsonObject());
            GitProtocol.fields(node, Set.of("jobId", "commit", "processesClosed"));
            if (!GitProtocol.uuid(node, "jobId").equals(job) || !node.get("processesClosed").isBoolean()
                || !node.get("processesClosed").booleanValue())
                throw new IllegalArgumentException();
            if (node.get("commit").isNull())
                return Optional.empty();
            String commit = GitProtocol.text(node, "commit");
            if (!commit.matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException();
            return Optional.of(commit);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Git cleanup receipt is unconfirmed");
        }
    }
}
