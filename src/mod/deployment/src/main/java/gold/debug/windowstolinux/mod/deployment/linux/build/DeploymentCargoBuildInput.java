package gold.debug.windowstolinux.mod.deployment.linux.build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;

/** Two exact UTF-8 payload segments for one Cargo build-run. The helper starts each in its own unit. */
public final class DeploymentCargoBuildInput {
    public static final String HEADER = "# WTL_CARGO_PREFETCH_BYTES=";
    private static final Pattern FRAME = Pattern.compile("\\A" + HEADER + "([1-9][0-9]{0,5})\\n");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeploymentCargoBuildInput() { }

    public record Phases(String header, String prefetch, String build) { }
    public record Prefetch(UUID task, String archiveSha256, String lockMember, String policy, String runtime) { }

    static String render(String build, String archiveSha256, String lockMember, String policy, UUID task) {
        if (!archiveSha256.matches("[a-f0-9]{64}")
            || !lockMember.matches("(?:[^\\p{Cntrl}/]+/)*Cargo[.]lock")
            || java.util.Arrays.stream(lockMember.split("/")).anyMatch(part -> part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("Invalid source-bound Cargo.lock");
        var value = new Prefetch(task, archiveSha256, lockMember, policy, DeploymentMirrorRuntime.cargoRuntimeSource());
        String material = DeploymentMirrorTasks.materialPrefix(task);
        String prefetch;
        try {
            prefetch = material.lines().findFirst().map(line -> line + "\n").orElse("")
                + JSON.writeValueAsString(value) + "\n";
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode Cargo prefetch data", failure);
        }
        return HEADER + prefetch.getBytes(StandardCharsets.UTF_8).length + "\n" + prefetch + build;
    }

    /** Returns the exact two segments, rejecting ambiguous boundaries or UTF-8 character splits. */
    public static Optional<Phases> phases(String input) {
        if (!input.startsWith(HEADER)) return Optional.empty();
        var frame = FRAME.matcher(input);
        if (!frame.find()) throw new IllegalArgumentException("Invalid Cargo prefetch frame");
        byte[] body = input.substring(frame.end()).getBytes(StandardCharsets.UTF_8);
        int boundary = Integer.parseInt(frame.group(1));
        if (boundary >= body.length) throw new IllegalArgumentException("Incomplete Cargo build phase");
        String prefetch = decode(body, 0, boundary);
        String build = decode(body, boundary, body.length - boundary);
        if (!prefetch.endsWith("\n") || !build.endsWith("\n"))
            throw new IllegalArgumentException("Incomplete Cargo phase lines");
        return Optional.of(new Phases(frame.group(), prefetch, build));
    }

    public static Prefetch prefetch(Phases phases) {
        String input = phases.prefetch();
        try {
            String document = input.startsWith("# WTL_MIRROR_BINDING=") ? input.substring(input.indexOf('\n') + 1) : input;
            var value = JSON.readValue(document, Prefetch.class);
            UUID task = value.task();
            if (task == null) throw new IllegalArgumentException("Cargo prefetch task is absent");
            DeploymentMirrorTasks.requireCaptured(task);
            if (!render(phases.build(), value.archiveSha256(), value.lockMember(), DeploymentMirrorTasks.policy(task), task)
                .equals(phases.header() + input + phases.build()))
                throw new IllegalArgumentException("Cargo prefetch data differs from its original runtime or policy");
            return value;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot decode Cargo prefetch data", failure);
        }
    }

    public static Prefetch prefetch(Phases phases, UUID task) {
        var value = prefetch(phases);
        if (!value.task().equals(task)) throw new IllegalArgumentException("Cargo prefetch task differs from the original");
        return value;
    }

    private static String decode(byte[] body, int offset, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body, offset, length)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Cargo phase boundary splits the UTF-8 input", failure);
        }
    }
}
