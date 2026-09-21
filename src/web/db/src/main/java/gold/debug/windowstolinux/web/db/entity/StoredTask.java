package gold.debug.windowstolinux.web.db.entity;

/**
 * Carries durable task identity and state; request and result JSON exclude credential values.
 * <p>携带持久化任务身份及状态；请求和结果 JSON 排除凭据值。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
 * @param requestJson request json / 请求JSON
 * @param resultJson result json / 结果JSON
 * @param errorCode error code / 错误代码
 * @param createdAt instant at which this record was created / 当前记录创建时刻
 * @param updatedAt updated at / 已更新时刻
 * @param finishedAt finished at / 已完成时刻
 */
public record StoredTask(String id, String kind, String state, String requestJson, String resultJson,
                         String errorCode, String createdAt, String updatedAt, String finishedAt) { }
