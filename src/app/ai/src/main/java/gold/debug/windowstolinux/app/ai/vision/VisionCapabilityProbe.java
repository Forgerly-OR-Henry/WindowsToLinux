package gold.debug.windowstolinux.app.ai.vision;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

import javax.imageio.ImageIO;

import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiMessage;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiRole;
import gold.debug.windowstolinux.appapi.ai.vision.AiImage;

/** Generates a fresh visual challenge with a platform-owned expected answer. / 生成带平台持有预期答案的全新视觉挑战。
 * @param request bounded visual provider request / 有界视觉提供者请求
 * @param expected exact platform-owned answer / 平台持有的精确答案 */
public record VisionCapabilityProbe(AiRequest request, String expected) {
    /** Builds a fresh PNG interpretation challenge. / 构造全新的 PNG 识别挑战。
     * @param outputLimit independent positive verification budget / 独立正数验证额度
     * @return generated visual challenge / 生成的视觉挑战
     * @throws IOException if the generated PNG cannot be encoded / 无法编码生成的 PNG 时 */
    public static VisionCapabilityProbe create(int outputLimit) throws IOException {
        if (outputLimit < 1)
            throw new IllegalArgumentException("Model output limit must be positive");
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
        String expected = String.join(" ", colors.subList(0, 3));
        try {
            var message = new AiMessage(AiRole.USER,
                "Identify the three solid colors from left to right. Reply with exactly three uppercase names separated by spaces. Allowed names: RED, GREEN, BLUE, YELLOW, CYAN, MAGENTA, BLACK, WHITE.",
                List.of(new AiImage("image/png", bytes)));
            return new VisionCapabilityProbe(new AiRequest(AiPurpose.VISION, List.of(message), outputLimit,
                Duration.ofSeconds(30), false), expected);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
