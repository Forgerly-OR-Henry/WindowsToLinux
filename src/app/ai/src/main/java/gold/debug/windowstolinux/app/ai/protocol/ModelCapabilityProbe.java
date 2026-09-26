package gold.debug.windowstolinux.app.ai.protocol;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

import javax.imageio.ImageIO;

import gold.debug.windowstolinux.appapi.ai.*;

/** Fresh text or image challenge without deployment-specific facts. / 不含部署业务事实的全新文字或图像挑战。
 * @param request bounded provider request / 有界提供者请求
 * @param expected exact platform-owned answer / 平台持有的精确答案 */
public record ModelCapabilityProbe(AiRequest request, String expected) {
    /** Builds an explicit capability challenge. / 构造明确的能力挑战。
     * @param vision whether image interpretation is required / 是否要求图像理解
     * @param outputLimit configured positive output ceiling / 已配置的正输出上限
     * @return generated challenge / 生成的挑战
     * @throws IOException if the generated PNG cannot be encoded / 无法编码生成的 PNG 时 */
    public static ModelCapabilityProbe create(boolean vision, int outputLimit) throws IOException {
        if (outputLimit < 1)
            throw new IllegalArgumentException("Model output limit must be positive");
        String expected;
        AiMessage message;
        if (vision) {
            var colors = new ArrayList<>(
                    List.of("RED", "GREEN", "BLUE", "YELLOW", "CYAN", "MAGENTA", "BLACK", "WHITE"));
            Collections.shuffle(colors, new java.security.SecureRandom());
            var values = Map.of("RED", Color.RED, "GREEN", Color.GREEN, "BLUE", Color.BLUE, "YELLOW", Color.YELLOW,
                    "CYAN", Color.CYAN, "MAGENTA", Color.MAGENTA, "BLACK", Color.BLACK, "WHITE", Color.WHITE);
            var image = new BufferedImage(360, 120, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                for (int i = 0; i < 3; i++) {
                    graphics.setColor(values.get(colors.get(i)));
                    graphics.fillRect(i * 120, 0, 120, 120);
                }
            } finally {
                graphics.dispose();
            }
            byte[] bytes;
            try (var output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "png", output))
                    throw new IOException("PNG encoding is unavailable");
                bytes = output.toByteArray();
            }
            expected = String.join(" ", colors.subList(0, 3));
            try {
                message = new AiMessage(AiRole.USER,
                        "Identify the three solid colors from left to right. Reply with exactly three uppercase names separated by spaces. Use only RED GREEN BLUE YELLOW CYAN MAGENTA BLACK WHITE.",
                        List.of(new AiImage("image/png", bytes)));
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } else {
            expected = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            message = new AiMessage(AiRole.USER, "Reply with exactly this code and nothing else: " + expected,
                    List.of());
        }
        return new ModelCapabilityProbe(new AiRequest(vision ? AiPurpose.VISION : AiPurpose.LANGUAGE, List.of(message),
                Math.min(64, outputLimit), Duration.ofSeconds(30), false), expected);
    }

    /** Checks the actual answer instead of accepting only an HTTP success. / 核验实际答案，而非只接受 HTTP 成功。
     * @param response complete provider response / 完整提供者响应
     * @return whether the generated challenge was answered / 是否答对生成的挑战 */
    public boolean accepts(AiResponse response) {
        return expected.equals(response.text().strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT));
    }
}
