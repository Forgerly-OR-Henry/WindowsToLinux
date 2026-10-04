package gold.debug.windowstolinux.app.ai.vision;

import java.util.Arrays;
import java.util.Base64;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.model.AiMessage;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;

/** Validates and encodes image attachments within the shared model message protocol. / 在共用模型消息协议内校验和编码图像附件。 */
public final class VisionMessageCodec {
    /** Prevents instances of the stateless codec. / 禁止无状态编解码器实例。 */
    private VisionMessageCodec() {
    }

    /** Encodes one visual message while retaining the whole-request image bound. / 编码一条视觉消息并保留整请求图像上限。
     * @param item shared message envelope / 共用消息信封
     * @param message immutable text and image attachments / 不可变文字及图像附件
     * @param purpose explicit requested capability use / 明确请求的能力用途
     * @param imageCount images already encoded in earlier messages / 前序消息已编码的图像数
     * @return accumulated whole-request image count / 累计整请求图像数
     * @throws IllegalArgumentException if purpose, format or bounds are invalid / 用途、格式或上限无效时 */
    public static int encode(ObjectNode item, AiMessage message, AiPurpose purpose, int imageCount) {
        if (purpose != AiPurpose.VISION)
            throw new IllegalArgumentException("Images require VISION purpose");
        var content = item.putArray("content");
        content.addObject().put("type", "text").put("text", message.text());
        for (var image : message.images()) {
            if (++imageCount > 4)
                throw new IllegalArgumentException("At most four images are accepted");
            byte[] bytes = image.content();
            try {
                boolean png = image.mediaType().equals("image/png") && bytes.length >= 8 && Arrays
                    .equals(Arrays.copyOf(bytes, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
                boolean jpeg = image.mediaType().equals("image/jpeg") && bytes.length >= 3
                    && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
                if (bytes.length > 10 * 1024 * 1024 || !png && !jpeg)
                    throw new IllegalArgumentException("Image type or size is invalid");
                content.addObject().put("type", "image_url").putObject("image_url").put("url",
                    "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(bytes));
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
        return imageCount;
    }
}
