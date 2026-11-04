package gold.debug.windowstolinux.mod.deployment.linux.mirror.locked;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandProtocol;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

final class DeploymentMirrorCredentialsTest {
    @Test
    void validatesTransportPayloadWithoutAllowingSystemBearerOrNewlineInjection() {
        byte[] result = DeploymentMirrorCredentials.material(bytes("{\"formatVersion\":1,\"authorization\":\"Bearer fixture-token\"}"), false);
        assertEquals("WTL-MIRROR-1\nBearer fixture-token\n", new String(result, StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorCredentials.material(bytes("{\"formatVersion\":1,\"authorization\":\"Bearer fixture-token\"}"), true));
        assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorCredentials.material(bytes("{\"formatVersion\":1,\"authorization\":\"Basic dXNlcjpwYXNz\\nInjected: value\"}"), false));
        assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorCredentials.material(bytes("{\"formatVersion\":1,\"authorization\":\"Basic bm8tY29sb24=\"}"), true));
        assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorCredentials.material(bytes("{\"formatVersion\":1,\"caPem\":\"untrusted text\"}"), false));
        assertEquals("WTL-MIRROR-1\nBasic dXNlcjpwYXNz\n", new String(DeploymentMirrorCredentials.material(
            bytes("{\"formatVersion\":1,\"authorization\":\"Basic dXNlcjpwYXNz\"}"), true), StandardCharsets.UTF_8));
    }

    @Test
    void opaqueCommandEvidenceNeverContainsMaterialAndRejectsChangedBindingOrInput() throws Exception {
        var id = UUID.randomUUID();
        var task = (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class},
            (p, method, args) -> { if (method.getName().equals("id")) return id; throw new AssertionError(method.getName()); });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class},
            (p, method, args) -> { throw new AssertionError("No runtime capability expected: " + method.getName()); });
        var server = new ServerId("saved-server");
        var layout = DeploymentRemoteLayout.original();
        String policy = "{\"task\":\"" + id + "\",\"server\":\"saved-server\",\"points\":{\"NODE_PACKAGE\":[{\"secretRef\":\"repository:1\"}]}}";
        byte[] material = bytes("WTL-MIRROR-1\nBearer unprintable-fixture-secret\n");
        String directory = DeploymentMirrorCredentials.directory(layout, id.toString(), sha(server.id()), sha(policy));
        try (var request = DeploymentMirrorCredentials.request(id.toString(), server.id(), policy, layout, "repository:1", material)) {
            var intent = RemoteActions.command(server, sha(policy), request).withSemantics(new ActionSemantics(
                DeploymentMirrorCredentials.STAGE, Set.of(new ActionTarget(server.id(), "task", id.toString()),
                    new ActionTarget(server.id(), "deployment-mirror-material", directory))));
            var evidence = DeploymentMirrorCredentials.inspect(intent, request, layout, task, policy);
            assertEquals("OPAQUE_BOUND_DATA", evidence.get("inputKind"));
            assertFalse(evidence.toString().contains("unprintable-fixture-secret"));
            assertFalse(request.script().contains("unprintable-fixture-secret"));
            assertThrows(IllegalArgumentException.class, () -> new DeploymentFixedCommandProtocol(context).inspect(intent, request, layout, task));
            assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorCredentials.inspect(intent, request, layout, task, policy + " "));
            try (var changed = new CommandRequest(request.script(), bytes("different protected input"), request.timeout(), request.outputLimitBytes())) {
                assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorCredentials.inspect(intent, changed, layout, task, policy));
            }
            var wrongTask = (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class},
                (p, method, args) -> UUID.randomUUID());
            assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorCredentials.inspect(intent, request, layout, wrongTask, policy));
        }
        String prefix = DeploymentMirrorCredentials.environmentPrefix(policy, layout);
        assertTrue(prefix.contains("# WTL_MIRROR_BINDING=" + id + "/" + sha(server.id()) + "/" + sha(policy)));
        assertTrue(prefix.contains(directory));
        assertFalse(prefix.contains("unprintable-fixture-secret"));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
    }
}
