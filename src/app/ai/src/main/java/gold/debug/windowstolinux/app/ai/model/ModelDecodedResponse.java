package gold.debug.windowstolinux.app.ai.model;

import gold.debug.windowstolinux.appapi.ai.model.AiResponse;

/** Internal provider state. It must never be put into diagnostic output. */
public record ModelDecodedResponse(AiResponse response, String continuation) {
    @Override
    public String toString() {
        return "ModelDecodedResponse[provider state withheld]";
    }
}
