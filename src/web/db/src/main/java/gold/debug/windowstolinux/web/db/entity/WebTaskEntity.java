package gold.debug.windowstolinux.web.db.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Carries persistent task columns for the scoped Web repository.
 * <p>携带限定作用域 Web 仓库使用的任务持久化列。
 *
 * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param createdBy identifier of the user who created the resource / 创建资源的用户标识
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
 * @param requestJson request json / 请求JSON
 * @param resultJson result json / 结果JSON
 * @param sourceId source id / 源码标识
 * @param applicationId managed application identifier / 受管应用标识
 * @param backupId backup id / 备份标识
 * @param errorCode error code / 错误代码
 * @param createdAt instant at which this record was created / 当前记录创建时刻
 * @param updatedAt updated at / 已更新时刻
 * @param finishedAt finished at / 已完成时刻
 */
@TableName("tasks")
public record WebTaskEntity(String workspaceId, String id, String createdBy, String kind, String state,
        String requestJson, String resultJson, String sourceId, String applicationId, String backupId,
        String errorCode, String createdAt, String updatedAt, String finishedAt) {
    /**
     * Builds stored task from the supplied stored inputs.
     * <p>根据所提供已存储输入构建已存储任务。
     *
     * @return stored task from the supplied stored inputs / 根据所提供已存储输入构建已存储任务
     */
    public StoredTask stored() {
        return new StoredTask(id, kind, state, requestJson, resultJson, errorCode, createdAt, updatedAt, finishedAt);
    }
}
