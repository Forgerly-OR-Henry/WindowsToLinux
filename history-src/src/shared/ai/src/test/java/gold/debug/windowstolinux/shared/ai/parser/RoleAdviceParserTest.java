package gold.debug.windowstolinux.shared.ai.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gold.debug.windowstolinux.shared.ai.collaboration.advice.AiAdviceDecision;
import org.junit.jupiter.api.Test;

class RoleAdviceParserTest {
    private final RoleAdviceParser parser = new RoleAdviceParser();

    @Test
    void acceptsOnlyTheExactBoundedSchema() {
        var advice = parser.parse("""
                {"decision":"CLEAR","summary":"No additional risk","findings":["Reviewed facts only"]}
                """);

        assertEquals(AiAdviceDecision.CLEAR, advice.decision());
        assertEquals(1, advice.findings().size());
    }

    @Test
    void rejectsExtraFieldsWrongOrderAndTooManyFindings() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"decision\":\"CLEAR\",\"summary\":\"ok\",\"findings\":[],\"authorize\":true}"));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"summary\":\"ok\",\"decision\":\"CLEAR\",\"findings\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "{\"decision\":\"CLEAR\",\"summary\":\"ok\",\"findings\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}"));
    }
}
