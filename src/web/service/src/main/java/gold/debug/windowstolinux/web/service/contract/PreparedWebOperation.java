package gold.debug.windowstolinux.web.service.contract;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Carries server-prepared work whose resource identifiers and physical target locks are resolved before persistence.
 * <p>携带服务器准备的工作，其资源标识及物理目标锁在持久化前已解析。
 *
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
 * @param serverIds server ids / 服务器标识集合
 * @param lockKeys lock keys / 锁键集合
 * @param mutating mutating / 变更
 * @param sourceId source id / 源码标识
 * @param applicationId managed application identifier / 受管应用标识
 * @param backupId backup id / 备份标识
 * @param work work / 工作
 */
public record PreparedWebOperation(String kind, JsonNode request, List<String> serverIds, List<String> lockKeys,
                                   boolean mutating, String sourceId, String applicationId, String backupId, WebWork work) {
    /**
     * Validates and binds the inputs required by prepared web operation.
     * <p>校验并绑定已准备Web操作所需输入。
     *
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param serverIds server ids / 服务器标识集合
     * @param lockKeys lock keys / 锁键集合
     * @param mutating mutating / 变更
     * @param sourceId source id / 源码标识
     * @param applicationId managed application identifier / 受管应用标识
     * @param backupId backup id / 备份标识
     * @param work work / 工作
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public PreparedWebOperation {
        request = request.deepCopy(); serverIds = List.copyOf(serverIds); lockKeys = List.copyOf(lockKeys); Objects.requireNonNull(work);
    }
    /**
     * Runs prepared Web work with its task interaction boundary.
     * <p>通过任务交互边界执行已准备的 Web 工作。
     */
    @FunctionalInterface public interface WebWork {
    /**
     * Executes json node.
     * <p>执行JSON节点。
     *
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
     JsonNode execute(TaskInteraction interaction) throws Exception; }
}
