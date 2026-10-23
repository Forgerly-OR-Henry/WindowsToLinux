package gold.debug.windowstolinux.mod.deployment.service.core.application;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Reads only caller-bound nonsecret configured hosts for local usage projection. / 仅为本地用法投影读取绑定调用方的非秘密已配置主机。 */
final class DeploymentApplicationHosts {
    /** Prevents instance state for one current discovery. / 避免一次当前发现拥有实例状态。 */
    private DeploymentApplicationHosts() {
    }

    /** Reads a complete bounded discovery without granting server use or opening SSH. / 不授予服务器使用权或打开SSH地读取完整有界发现。
     * @param context current caller-bound server capability / 当前绑定调用方的服务器能力
     * @param task authentic current short read task / 真实当前短读取任务
     * @return exact saved IDs and current public hosts / 精确保存ID与当前公开主机
     * @throws ApiException if discovery is incomplete or ambiguous / 发现不完整或有歧义时 */
    static Map<ServerId, String> read(ModContext context, TaskContext task) throws ApiException {
        var hosts = new HashMap<ServerId, String>();
        Optional<String> cursor = Optional.empty();
        do {
            var page = context.servers().listConfigured(cursor, 200, task);
            for (var server : page.items())
                if (hosts.putIfAbsent(server.ref(), server.host()) != null)
                    throw unavailable();
            cursor = page.nextCursor();
        } while (cursor.isPresent() && hosts.size() < 10000);
        if (cursor.isPresent() || hosts.size() > 10000)
            throw unavailable();
        return Map.copyOf(hosts);
    }

    /** Requires the exact original ID without a guessed endpoint. / 要求精确原ID，不猜测端点。
     * @param hosts complete current discovery / 完整当前发现
     * @param server exact original saved identity / 精确原保存身份
     * @return current nonsecret host / 当前非秘密主机
     * @throws ApiException if the original server is unavailable / 原服务器不可用时 */
    static String require(Map<ServerId, String> hosts, ServerId server) throws ApiException {
        var host = hosts.get(server);
        if (host == null)
            throw unavailable();
        return host;
    }

    /** Keeps missing or truncated discovery explicit. / 明确保留缺失或截断的发现。
     * @return bounded current-host refusal / 有界当前主机拒绝 */
    private static ApiException unavailable() {
        return new ApiException(new Problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_USAGE_HOST_UNAVAILABLE",
            "Current configured host cannot be confirmed", Map.of(), Optional.empty()));
    }
}
