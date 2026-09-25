package gold.debug.windowstolinux.api.ai;

import java.util.List;

/**
 * Ordered model message with copied image attachments. / 包含独立图像附件列表的有序模型消息。
 * @param role role of this model conversation message / 此模型对话消息的角色
 * @param text message or response text / 消息或响应文本
 * @param images copied image attachments in message order / 按消息顺序复制的图像附件
 */
public record AiMessage(AiRole role, String text, List<AiImage> images) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param role role of this model conversation message / 此模型对话消息的角色
     * @param text message or response text / 消息或响应文本
     * @param images copied image attachments in message order / 按消息顺序复制的图像附件
     */
    public AiMessage {
        java.util.Objects.requireNonNull(role, "role");
        java.util.Objects.requireNonNull(text, "text");
        images = List.copyOf(images);
    }
}
