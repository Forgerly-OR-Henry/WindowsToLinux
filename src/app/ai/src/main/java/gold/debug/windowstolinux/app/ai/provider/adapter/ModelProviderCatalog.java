package gold.debug.windowstolinux.app.ai.provider.adapter;

import java.util.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.provider.*;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Construction entry for startup injection. / 启动注入的构造入口。
 */
public final class ModelProviderCatalog {
    /** Captures inputs at the ModelProviderCatalog ownership boundary. / 在 ModelProviderCatalog 所有权边界捕获输入。
     */
    private ModelProviderCatalog() {
    }

    /** Builds the complete set of explicit manufacturer adapters for injection. / 构造供注入使用的完整显式厂商适配集合。
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public static ModelProviderRegistry create() {
        return new ModelProviderRegistry(List.of(new OpenAiModelAdapter(), new AnthropicModelAdapter(), new GeminiModelAdapter(), new GrokModelAdapter(), new DeepSeekModelAdapter(), new QwenModelAdapter(), new ThirdPartyModelAdapter()));
    }
}
