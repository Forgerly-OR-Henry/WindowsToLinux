package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentAgentContinuationSource;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** A bounded nonsecret successor intent and its permanent, uniquely claimable admission record. */
public final class DeploymentAgentContinuation {
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public static final String TICKET_PREFIX = "deployment.agent.next.v1.";

    public static final String ROOT_PREFIX = "deployment.agent.goal.v1.";

    public record Source(UUID capsule, UUID originalSnapshot, String source, String manifest, long bytes, int entries) {
        public Source {
            Objects.requireNonNull(capsule);
            Objects.requireNonNull(originalSnapshot);
            digest(source);
            digest(manifest);
            if (bytes < 0 || entries < 0) throw new IllegalArgumentException("Invalid source custody facts");
        }

        public static Source capture(DeploymentAgentContinuationSource value) {
            return new Source(value.id(), value.originalSnapshot(), value.source(), value.manifest(), value.bytes(), value.entries());
        }
    }

    public record Intent(UUID root, UUID predecessor, UUID ticket, UUID operation, int ordinal,
                         String application, String server, String target, String configuration,
                         String targetProfile, String physicalTarget, String layout, String layoutSha256,
                         DeploymentAgentInputs.Request request, Source source, Map<String, String> resources,
                         String acceptedFailure, String nativeFailureObservation,
                         DeploymentAgentGoalBudget.Snapshot allowance) {
        public Intent {
            for (var id : List.of(root, predecessor, ticket, operation)) Objects.requireNonNull(id);
            for (var value : List.of(target, configuration, targetProfile, physicalTarget, layoutSha256)) digest(value);
            Objects.requireNonNull(request);
            Objects.requireNonNull(source);
            Objects.requireNonNull(allowance);
            resources = Collections.unmodifiableMap(new TreeMap<>(resources));
            resources.values().forEach(DeploymentAgentContinuation::digest);
            if (ordinal < 2 || !application.matches("[a-z0-9][a-z0-9-]{0,62}") || server.isBlank()
                || operation.equals(root) || operation.equals(predecessor) || source.bytes() > request.sourceBytes()
                || source.entries() > request.sourceEntries() || !source.source().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid successor identity or original limits");
        }

        public String sha256() {
            return DeploymentAgentInputs.hash(encode(this));
        }

        public DeploymentAgentWorkspaceBinding.Binding binding() {
            return new DeploymentAgentWorkspaceBinding.Binding(root, predecessor, ticket, operation, application,
                source.source(), layoutSha256, target, configuration, sha256());
        }
    }

    public record Ticket(Intent intent, String phase, UUID child, DeploymentAgentGoalBudget.Snapshot settledAllowance) {
        public Ticket {
            Objects.requireNonNull(intent);
            if (!Set.of("RESERVED", "READY", "CLAIMED", "BLOCKED").contains(phase)
                || phase.equals("CLAIMED") && child == null || !Set.of("CLAIMED", "BLOCKED").contains(phase) && child != null
                || child != null && (child.equals(intent.predecessor()) || child.equals(intent.operation())))
                throw new IllegalArgumentException("Invalid durable successor state");
            if (settledAllowance != null) requireCarry(intent.allowance(), settledAllowance);
            if (Set.of("READY", "CLAIMED").contains(phase) && settledAllowance == null)
                throw new IllegalArgumentException("Original final goal allowance is absent");
        }
    }

    public record Root(UUID root, UUID activeLeaf, int ordinal) {
        public Root {
            Objects.requireNonNull(root);
            Objects.requireNonNull(activeLeaf);
            if (ordinal < 1) throw new IllegalArgumentException("Invalid goal leaf ordinal");
        }
    }

    public record Claim(Ticket ticket, boolean first) {
    }

    public static void requireCarry(DeploymentAgentGoalBudget.Snapshot before, DeploymentAgentGoalBudget.Snapshot after) {
        if (before.maximumResponses() != after.maximumResponses() || before.maximumTools() != after.maximumTools()
            || before.build().limitNanos() != after.build().limitNanos() || before.mainResponses() > after.mainResponses()
            || before.nestedResponses() > after.nestedResponses() || before.mainTools() > after.mainTools()
            || before.sharedTools() > after.sharedTools() || before.hostCommands() > after.hostCommands()
            || before.declaredHostSeconds() > after.declaredHostSeconds() || before.build().usedNanos() > after.build().usedNanos())
            throw new IllegalStateException("Original goal allowance cannot be replaced, refunded or increased");
    }

    /** Exact original persistent bindings; external initialization and secrets have no inherited authority. */
    public static Map<String, String> resources(DeploymentPublishResult proposal) {
        var result = new TreeMap<String, String>();
        for (var part : proposal.graph().components()) {
            if (part.resources().databaseBindings().isEmpty())
                throw new IllegalArgumentException("Original database scope is unknown");
            for (var file : part.resources().fileBindings())
                if (!file.seedFile().isEmpty())
                    throw new IllegalArgumentException("Successor cannot inherit external file seeds");
            for (var database : part.resources().databaseBindings().orElseThrow()) {
                if (!(database.connection() instanceof DeploymentDatabaseConnection.Sqlite sqlite)
                    || !sqlite.seedFile().isEmpty() || !sqlite.initializationFiles().isEmpty())
                    throw new IllegalArgumentException("Online successor supports only original unseeded SQLite bindings");
            }
            result.put(part.componentId(), DeploymentAgentInputs.hash(part.resources().toString()));
        }
        for (var unit : proposal.units()) {
            if (!unit.secrets().isEmpty())
                throw new IllegalArgumentException("Online successor cannot inherit a secret configuration");
        }
        return Collections.unmodifiableMap(result);
    }

    public static void requireResources(Intent original, DeploymentPublishResult current) {
        if (!original.application().equals(current.graph().applicationId()) || !original.resources().equals(resources(current)))
            throw new IllegalArgumentException("Successor application or persistent bindings differ from the original scope");
    }

    public static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Nonsecret successor context cannot be encoded", invalid);
        }
    }

    public static <T> T decode(String value, Class<T> type) throws Exception {
        T result = JSON.readValue(value, type);
        if (!encode(result).equals(value)) throw new IllegalArgumentException("Noncanonical successor context");
        return result;
    }

    private static void digest(String value) {
        if (!Objects.requireNonNull(value).matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid successor digest");
    }

    private DeploymentAgentContinuation() {
    }
}
