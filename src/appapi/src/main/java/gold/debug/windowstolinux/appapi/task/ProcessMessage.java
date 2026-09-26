package gold.debug.windowstolinux.appapi.task;

/**
 * Protocol message whose JSON object is validated before transport. / 传输前需要校验 JSON 对象的协议消息。
 * @param type owner-local task type / 所有者内部的任务类型
 * @param jsonObject JSON object payload checked before transmission / 传输前校验的 JSON 对象载荷
 */
public record ProcessMessage(String type, String jsonObject) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param type owner-local task type / 所有者内部的任务类型
     * @param jsonObject JSON object payload checked before transmission / 传输前校验的 JSON 对象载荷
     */
    public ProcessMessage {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(jsonObject, "jsonObject");
    }
}
