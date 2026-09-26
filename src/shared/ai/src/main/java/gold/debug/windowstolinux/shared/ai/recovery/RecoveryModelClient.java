package gold.debug.windowstolinux.shared.ai.recovery;

import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.ai.recovery.RecoveryProtocol;
import gold.debug.windowstolinux.shared.ai.provider.ProviderEndpointPolicy;
import gold.debug.windowstolinux.shared.ai.transport.*;

/**
 * Bounded observations and proposals, with no execution capability. / 有界观察与建议，不提供执行能力。
 */
public final class RecoveryModelClient {
    /**
     * Classifies the transport and response outcome of one recovery model request.
     * <p>分类一次救援模型请求的传输及响应结果。
     */
    public enum OutcomeStatus {
        /**
         * VALID classification within outcome status.
         * <p>结果状态中的有效分类。
         */
        VALID,
        /**
         * UNSUPPORTED classification within outcome status.
         * <p>结果状态中的不支持分类。
         */
        UNSUPPORTED,
        /**
         * UNAVAILABLE classification within outcome status.
         * <p>结果状态中的不可用分类。
         */
        UNAVAILABLE,
        /**
         * INVALID classification within outcome status.
         * <p>结果状态中的无效分类。
         */
        INVALID
    }

    /**
     * Carries a recovery model response with its transport classification.
     * <p>携带救援模型响应及其传输分类。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param content content / 内容
     */
    public record Reply(OutcomeStatus status, String content) {
        /**
         * Returns the diagnostic text representation of this object.
         * <p>返回当前对象的诊断文本表示。
         *
         * @return the diagnostic text representation of this object / 当前对象的诊断文本表示
         */
        @Override
        public String toString() {
            return "Reply[" + status + "]";
        }
    }
    /**
     * JSON mapper for recovery model client.
     * <p>恢复模型客户端使用的 JSON 映射器。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Transport.
     * <p>传输。
     */
    private final RoleChatTransport transport;
    /**
     * Initializes recovery model client through its shared constructor contract.
     * <p>通过共享构造契约初始化恢复模型客户端。
     */
    public RecoveryModelClient() {
        this(new HttpRoleChatTransport());
    }

    /**
     * Validates and binds the inputs required by recovery model client.
     * <p>校验并绑定恢复模型客户端所需输入。
     *
     * @param transport transport / 传输
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RecoveryModelClient(RoleChatTransport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    /**
     * Observes reply.
     * <p>观测回复。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param key API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @param image image / 镜像
     * @return constructed or resolved reply / 构造或解析得到的回复
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    public Reply observe(URI endpoint, String model, char[] key, byte[] image) throws InterruptedException {
        if (image.length == 0 || image.length > 4 * 1024 * 1024)
            return new Reply(OutcomeStatus.INVALID, "");
        return request(endpoint, model, key, RecoveryProtocol.OBSERVATION_INSTRUCTION,
                List.of(Map.of("type", "text", "text", RecoveryProtocol.OBSERVATION_REQUEST),
                        Map.of("type", "image_url", "image_url",
                                Map.of("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(image)))),
                true);
    }

    /**
     * Builds reply from the supplied propose inputs.
     * <p>根据所提供建议输入构建回复。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param key API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @return reply from the supplied propose inputs / 根据所提供建议输入构建回复
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    public Reply propose(URI endpoint, String model, char[] key, String facts) throws InterruptedException {
        if (facts.length() > 40000)
            return new Reply(OutcomeStatus.INVALID, "");
        return request(endpoint, model, key, RecoveryProtocol.ACTION_INSTRUCTION, facts, false);
    }

    /**
     * Sends a bounded recovery-model request, classifies transport and response failures and retains only the transient reply needed by the caller.
     * <p>发送有界救援模型请求，分类传输及响应失败，并仅保留调用方所需临时回复。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param key API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @param instruction instruction / 指令
     * @param content content / 内容
     * @param image image / 镜像
     * @return constructed or resolved reply / 构造或解析得到的回复
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    private Reply request(URI endpoint, String model, char[] key, String instruction, Object content, boolean image)
            throws InterruptedException {
        try {
            new ProviderEndpointPolicy().validateEndpoint(endpoint);
            new ProviderEndpointPolicy().requireModel(model);
            String body = JSON.writeValueAsString(Map.of("model", model, "messages", List
                    .of(Map.of("role", "system", "content", instruction), Map.of("role", "user", "content", content))));
            var response = transport.send(endpoint, key, body);
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException();
            if (response.statusCode() != 200) {
                String error = response.body().toLowerCase(Locale.ROOT);
                boolean unsupported = image && (response.statusCode() == 400 || response.statusCode() == 422)
                        && (error.contains("image") || error.contains("vision") || error.contains("multimodal"))
                        && (error.contains("not support") || error.contains("unsupported")
                                || error.contains("text only"));
                return new Reply(unsupported ? OutcomeStatus.UNSUPPORTED : OutcomeStatus.UNAVAILABLE, "");
            }
            if (response.body().length() > 512 * 1024)
                return new Reply(OutcomeStatus.INVALID, "");
            JsonNode value = JSON.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!value.isTextual() || value.textValue().length() > 32000)
                return new Reply(OutcomeStatus.INVALID, "");
            String result = value.textValue();
            if (image)
                RecoveryProtocol.parseObservation(result);
            else
                RecoveryProtocol.parseAction(result);
            return new Reply(OutcomeStatus.VALID, result);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return new Reply(OutcomeStatus.INVALID, "");
        } catch (Exception failure) {
            return new Reply(failure instanceof java.io.IOException ? OutcomeStatus.UNAVAILABLE : OutcomeStatus.INVALID,
                    "");
        }
    }

    /**
     * Sends a generated visual challenge to verify that the selected provider actually accepts and interprets image input.
     * <p>发送生成的视觉挑战，以验证所选提供者实际接受并理解图像输入。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param key API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public void probeVision(URI endpoint, String model, char[] key) {
        try {
            var image = new java.awt.image.BufferedImage(360, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, 360, 100);
            String challenge = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            graphics.setColor(java.awt.Color.BLACK);
            graphics.setFont(new java.awt.Font("Monospaced", java.awt.Font.BOLD, 32));
            graphics.drawString(challenge, 20, 60);
            graphics.dispose();
            var bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", bytes);
            Reply reply = observe(endpoint, model, key, bytes.toByteArray());
            if (reply.status() != OutcomeStatus.VALID
                    || !RecoveryProtocol.parseObservation(reply.content()).contains(challenge))
                throw new IllegalArgumentException("vision probe failed");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("vision probe cancelled");
        } catch (Exception failure) {
            throw new IllegalArgumentException("vision probe failed");
        }
    }
}
