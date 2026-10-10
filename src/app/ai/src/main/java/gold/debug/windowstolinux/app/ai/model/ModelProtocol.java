package gold.debug.windowstolinux.app.ai.model;

/** Explicit wire protocol; never inferred from an endpoint or model name. */
public enum ModelProtocol {
    OPENAI_CHAT_COMPLETIONS,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES
}
