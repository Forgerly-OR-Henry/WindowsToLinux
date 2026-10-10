package gold.debug.windowstolinux.app.ai.model;

/** Numeric-only pre-send refusal; never contains the encoded request or private provider state. */
public final class ModelRequestBudgetException extends IllegalArgumentException {
    private final int encodedBytes;

    public ModelRequestBudgetException(int encodedBytes) {
        super("Encoded model request exceeds its byte budget");
        this.encodedBytes = encodedBytes;
    }

    public int encodedBytes() {
        return encodedBytes;
    }
}
