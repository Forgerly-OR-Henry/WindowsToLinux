package gold.debug.windowstolinux.web.service.interaction;

import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.web.service.contract.TaskInteraction;
import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import tools.jackson.databind.JsonNode;

/**
 * Handles typed task decisions and validates answers against the exact requested fields.
 * <p>处理类型化任务决策，并按精确请求字段校验回答。
 */
public final class WebTaskInteractionService {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private WebTaskInteractionService() {
    }

    /**
     * Requests explicit confirmation, validates the answer shape and rejects a declined decision.
     * <p>请求显式确认、校验回答结构，并拒绝未同意的决策。
     *
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param details details / 详情
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static void approve(TaskInteraction interaction, String code, JsonNode details) throws Exception {
        JsonNode answer = interaction.decide("CONFIRM",
                WebJsonCodec.object().put("code", code).set("details", details));
        WebRequestValidator.fields(answer, "accepted");
        if (!answer.path("accepted").isBoolean())
            throw new IllegalArgumentException("A decision is required");
        if (!answer.path("accepted").asBoolean())
            throw new CancellationException("User declined the operation");
    }

    /**
     * Requests only the declared non-secret fields and validates that answers belong to the exact requested field set and constraints.
     * <p>仅请求声明的非秘密字段，并校验回答属于精确请求字段集合且满足约束。
     *
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static Map<String, String> inputs(TaskInteraction interaction, List<DeploymentInputField> fields)
            throws Exception {
        if (fields.isEmpty())
            return Map.of();
        JsonNode answer = interaction.decide("INPUTS", WebJsonCodec.object().set("fields", WebJsonCodec.tree(fields)));
        WebRequestValidator.fields(answer, "values");
        JsonNode values = answer.path("values");
        WebRequestValidator.fields(values, fields.stream().map(DeploymentInputField::id).toArray(String[]::new));
        var result = new LinkedHashMap<String, String>();
        for (var field : fields) {
            if (!values.path(field.id()).isTextual())
                throw new IllegalArgumentException("A field answer is missing");
            String value = values.path(field.id()).asText();
            int limit = field.id().equals("applicationDeclaration") || field.id().endsWith("/applicationDeclaration")
                    ? 65536
                    : 4096;
            if (value.length() > limit || value.indexOf('\0') >= 0
                    || (!field.choices().isEmpty() && !field.choices().contains(value)))
                throw new IllegalArgumentException("Invalid field answer");
            result.put(field.id(), value);
        }
        return result;
    }

    /**
     * Publishes bounded progress information through the task interaction contract.
     * <p>通过任务交互契约发布有界进度信息。
     *
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param details details / 详情
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public static void progress(TaskInteraction interaction, String code, JsonNode details) {
        try {
            interaction.checkCancelled();
            interaction.progress(code, details);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot persist task progress", failure);
        }
    }

    /**
     * Confirms web task interaction.
     * <p>确认Web任务交互。
     *
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param details details / 详情
     * @return true when confirms web task interaction, false otherwise / 确认Web任务交互时为 true，否则为 false
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public static boolean confirm(TaskInteraction interaction, String code, JsonNode details) {
        try {
            approve(interaction, code, details);
            return true;
        } catch (CancellationException declined) {
            return false;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot obtain task decision", failure);
        }
    }
}
