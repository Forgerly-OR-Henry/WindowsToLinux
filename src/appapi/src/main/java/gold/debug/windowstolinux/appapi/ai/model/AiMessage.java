package gold.debug.windowstolinux.appapi.ai.model;

import java.util.List;

import gold.debug.windowstolinux.appapi.ai.vision.AiImage;

/**
 * Ordered model message with copied image attachments. / 包含独立图像附件列表的有序模型消息。
 * @param role role of this model conversation message / 此模型对话消息的角色
 * @param text message or response text / 消息或响应文本
 * @param images copied image attachments in message order / 按消息顺序复制的图像附件
 * @param toolCalls complete assistant native call batch / 完整助手原生调用批次
 * @param toolResult result of an original tool call / 原工具调用结果
 * @param continuation task-bound opaque provider state / 任务绑定的不透明提供方状态
 */
public record AiMessage(AiRole role, String text, List<AiImage> images, List<AiToolCall> toolCalls,
                        java.util.Optional<AiToolResult> toolResult,
                        java.util.Optional<AiContinuation> continuation) {
    public AiMessage(AiRole role, String text, List<AiImage> images) {
        this(role, text, images, List.of(), java.util.Optional.empty(), java.util.Optional.empty());
    }

    public static AiMessage assistant(AiResponse response) {
        return new AiMessage(AiRole.ASSISTANT, response.text(), List.of(), response.toolCalls(),
            java.util.Optional.empty(), response.continuation());
    }

    public static AiMessage result(AiToolResult result) {
        return new AiMessage(AiRole.TOOL, "", List.of(), List.of(), java.util.Optional.of(result),
            java.util.Optional.empty());
    }

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
        toolCalls = List.copyOf(toolCalls);
        java.util.Objects.requireNonNull(toolResult);
        java.util.Objects.requireNonNull(continuation);
        if ((!toolCalls.isEmpty() || continuation.isPresent()) && role != AiRole.ASSISTANT
            || toolResult.isPresent() != (role == AiRole.TOOL)
            || role == AiRole.TOOL && (!text.isEmpty() || !images.isEmpty()))
            throw new IllegalArgumentException("Native message role conflicts with its payload");
    }
}
