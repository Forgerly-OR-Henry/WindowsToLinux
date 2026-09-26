package gold.debug.windowstolinux.app.mod.process;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;

class ProcessProtocolTest {
    static ProcessProtocol.Frame frame(ProcessProtocol.Kind kind, String payload) {
        return new ProcessProtocol.Frame(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), kind,
                "example.inspect", payload);
    }

    @Test
    void utf8FramesRespectTheInclusiveByteLimitAndPartialEofIsNeverAFrame() throws Exception {
        var original = frame(ProcessProtocol.Kind.REQUEST, "{\"text\":\"中文😀\"}");
        assertEquals(original,
                ProcessProtocol.read(new ByteArrayInputStream(ProcessProtocol.encode(original))).orElseThrow());
        assertTrue(ProcessProtocol.read(new ByteArrayInputStream(new byte[0])).isEmpty());
        assertThrows(IOException.class,
                () -> ProcessProtocol.read(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))));
        var empty = frame(ProcessProtocol.Kind.REQUEST, "{\"text\":\"\"}");
        int room = ProcessProtocol.MAX_BYTES - ProcessProtocol.encode(empty).length;
        var exact = new ProcessProtocol.Frame(empty.process(), empty.task(), empty.request(), empty.kind(),
                empty.type(), "{\"text\":\"" + "a".repeat(room) + "\"}");
        assertEquals(ProcessProtocol.MAX_BYTES, ProcessProtocol.encode(exact).length);
        assertEquals(exact,
                ProcessProtocol.read(new ByteArrayInputStream(ProcessProtocol.encode(exact))).orElseThrow());
        var oversized = new ProcessProtocol.Frame(empty.process(), empty.task(), empty.request(), empty.kind(),
                empty.type(), "{\"text\":\"" + "a".repeat(room + 1) + "\"}");
        assertThrows(IOException.class, () -> ProcessProtocol.encode(oversized));
        assertThrows(IOException.class, () -> ProcessProtocol.read(new ByteArrayInputStream(
                ("a".repeat(ProcessProtocol.MAX_BYTES) + "\n").getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void ambiguousFieldsVersionsDirectionsAndEncodingsAreRejected() throws Exception {
        String valid = new String(ProcessProtocol.encode(frame(ProcessProtocol.Kind.REQUEST, "{}")),
                StandardCharsets.UTF_8);
        for (String invalid : List.of("\uFEFF" + valid, valid.replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
                valid.replace("\"protocolVersion\":1", "\"protocolVersion\":1,\"protocolVersion\":1"),
                valid.replace("\"payload\":{}", "\"payload\":{\"nested\":{\"a\":1,\"a\":2}}"),
                valid.replace("\"payload\":{}", "\"payload\":[]"),
                valid.replace("\"payload\":{}", "\"payload\":{},\"future\":true"),
                valid.replace("\"REQUEST\"", "\"GUESS\""), valid.stripTrailing() + " {}\n"))
            assertThrows(IOException.class, () -> ProcessProtocol.decode(invalid.getBytes(StandardCharsets.UTF_8)),
                    invalid);
        assertThrows(IOException.class, () -> ProcessProtocol.decode(new byte[]{(byte) 0xC3, (byte) 0x28, '\n'}));
        assertThrows(IllegalArgumentException.class, () -> frame(ProcessProtocol.Kind.REQUEST, "[]"));
        assertThrows(IllegalArgumentException.class, () -> frame(ProcessProtocol.Kind.REQUEST, "{\"x\":1,\"x\":2}"));
    }

    @Test
    void resultContractsAndObjectOrderAreVerifiedBeforeDelivery() throws Exception {
        var success = frame(ProcessProtocol.Kind.RESULT,
                "{\"outcome\":\"SUCCEEDED\",\"value\":{\"count\":2},\"problem\":null}");
        assertEquals(Outcome.SUCCEEDED, ProcessProtocol.result(success).outcome());
        var reordered = new ProcessProtocol.Frame(success.process(), success.task(), success.request(), success.kind(),
                success.type(), "{\"problem\":null,\"value\":{\"count\":2},\"outcome\":\"SUCCEEDED\"}");
        assertTrue(ProcessProtocol.equivalent(success, reordered));
        for (String invalid : List.of("{\"outcome\":\"FAILED\",\"value\":null,\"problem\":null}",
                "{\"outcome\":\"UNKNOWN\",\"value\":null,\"problem\":null}",
                "{\"outcome\":\"SUCCEEDED\",\"value\":[],\"problem\":null}",
                "{\"outcome\":\"SUCCEEDED\",\"value\":null,\"problem\":{},\"extra\":1}"))
            assertThrows(IOException.class, () -> ProcessProtocol.result(frame(ProcessProtocol.Kind.RESULT, invalid)));
        assertThrows(IOException.class,
                () -> ProcessProtocol.result(frame(ProcessProtocol.Kind.ERROR, success.payload())));
        String problem = "{\"code\":\"IO_FAILURE\",\"eventCode\":\"READ_FAILED\",\"message\":\"safe diagnostic\",\"context\":{\"stage\":\"read\"},\"diagnosticId\":null}";
        assertEquals(Outcome.FAILED, ProcessProtocol.result(frame(ProcessProtocol.Kind.ERROR,
                "{\"outcome\":\"FAILED\",\"value\":null,\"problem\":" + problem + "}")).outcome());
    }
}
