package gold.debug.windowstolinux.api.task;

/**
 * Host-owned review and input bound to the accepted task. / 绑定已接纳任务的宿主审核及输入交互。
 */
public interface InteractionService {

    /**
     * Requests review of the exact effect and returns a host-validated grant. / 请求审核精确副作用并返回宿主可验证的授权。
     * @param intent exact effect submitted for user review / 提交给用户审核的精确副作用
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) throws ApiException;

    /**
     * Requests input without allowing secret values into diagnostics. / 请求输入，秘密值不得进入诊断。
     * @param title human-readable task title / 可读的任务标题
     * @param prompt host-presented user input request / 由宿主展示的用户输入请求
     * @param secret opaque authorized secret reference or temporary secret value / 不透明的已授权秘密引用或临时秘密值
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task)
            throws ApiException;
}
