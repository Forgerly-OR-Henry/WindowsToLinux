package gold.debug.windowstolinux.shared.ai.redaction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AgentEvidenceTextTest {
    @Test void redactsBeforeTruncationAndPreservesUsefulErrorContext(){
        String secret="password=topsecret token:tokenvalue Authorization=Bearer privatevalue jdbc:postgresql://user:pass@host/db\n-----BEGIN PRIVATE KEY-----\nkeymaterial\n-----END PRIVATE KEY-----";
        String result=AgentEvidenceText.redact("build failed: "+secret);
        for(String value:new String[]{"topsecret","tokenvalue","privatevalue","user:pass","keymaterial"})assertFalse(result.contains(value),result);
        assertTrue(result.contains("build failed"));assertTrue(AgentEvidenceText.redact("a".repeat(9000)).length()<2100);
    }
}
