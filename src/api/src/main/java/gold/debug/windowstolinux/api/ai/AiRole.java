package gold.debug.windowstolinux.api.ai;

/**
 * Message role in an ordered model conversation. / 有序模型对话中的消息角色。
 */
public enum AiRole {
    /**
     * Model instructions supplied by the calling application. / 调用应用提供的模型指令。
     */
    SYSTEM,
    /**
     * Input supplied as a user conversation message. / 作为用户对话消息提供的输入。
     */
    USER,
    /**
     * A preceding model response in the conversation. / 对话中的先前模型响应。
     */
    ASSISTANT
}
