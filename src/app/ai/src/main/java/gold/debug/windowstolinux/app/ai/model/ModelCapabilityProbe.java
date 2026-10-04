package gold.debug.windowstolinux.app.ai.model;

import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiMessage;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiRole;

/** Shared capability challenge and answer check without deployment-specific facts. / 不含部署业务事实的共用能力挑战及答案核验。
 * @param request bounded provider request / 有界提供者请求
 * @param expected exact platform-owned answer / 平台持有的精确答案 */
public record ModelCapabilityProbe(AiRequest request, String expected) {
    /** Builds a fresh default text capability challenge. / 构造全新的默认文字能力挑战。
     * @param outputLimit configured positive output ceiling / 已配置的正输出上限
     * @return generated challenge / 生成的挑战 */
    public static ModelCapabilityProbe create(int outputLimit) {
        if (outputLimit < 1)
            throw new IllegalArgumentException("Model output limit must be positive");
        String expected = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        var message = new AiMessage(AiRole.USER, "Reply with exactly this code and nothing else: " + expected,
            List.of());
        return new ModelCapabilityProbe(new AiRequest(AiPurpose.LANGUAGE, List.of(message),
            Math.min(64, outputLimit), Duration.ofSeconds(30), false), expected);
    }

    /** Checks the actual answer instead of accepting only an HTTP success. / 核验实际答案，而非只接受 HTTP 成功。
     * @param response complete provider response / 完整提供者响应
     * @return whether the generated challenge was answered / 是否答对生成的挑战 */
    public boolean accepts(AiResponse response) {
        return expected.equals(response.text().strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT));
    }
}
