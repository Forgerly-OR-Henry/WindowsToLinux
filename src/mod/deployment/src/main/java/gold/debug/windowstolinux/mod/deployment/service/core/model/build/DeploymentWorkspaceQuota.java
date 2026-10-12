package gold.debug.windowstolinux.mod.deployment.service.core.model.build;

import java.util.*;

/** Pure bounded disk-budget arithmetic; callers own demand evidence and approval. */
public final class DeploymentWorkspaceQuota {
    public static final long UNIT = 64L * 1024 * 1024;

    public static final long MAXIMUM = 128L * 1024 * 1024 * 1024;

    public enum Mode {AUTO, EXPLICIT}

    public enum Cost {STANDARD, AGENT}

    public record Policy(long maximumBytes, long reserveBytes) {
        public Policy {
            if (maximumBytes < UNIT || maximumBytes > MAXIMUM || reserveBytes < 1024L * 1024 * 1024
                || reserveBytes > MAXIMUM) throw new IllegalArgumentException("Invalid workspace policy");
        }
    }

    public record Request(Mode mode, long minimumBuildBytes, long preferredBuildBytes, long explicitBytes) {
        public Request {
            Objects.requireNonNull(mode);
            if (minimumBuildBytes < 0 || preferredBuildBytes < minimumBuildBytes || preferredBuildBytes > MAXIMUM
                || mode == Mode.AUTO && explicitBytes != 0 || mode == Mode.EXPLICIT && (explicitBytes < UNIT || explicitBytes > MAXIMUM))
                throw new IllegalArgumentException("WORKSPACE_DEMAND_INVALID: bounded minimum/preferred or explicit bytes required");
        }

        public static Request automatic(long minimum, long preferred) {
            return new Request(Mode.AUTO, minimum, preferred, 0);
        }

        public static Request explicit(long bytes) {
            return new Request(Mode.EXPLICIT, 0, 0, bytes);
        }
    }

    public record Input(String id, long archiveBytes, long sourceFloorBytes, Request request) {
        public Input {
            Objects.requireNonNull(id);
            Objects.requireNonNull(request);
            if (archiveBytes < 0 || sourceFloorBytes < archiveBytes)
                throw new IllegalArgumentException("WORKSPACE_SOURCE_INVALID");
        }
    }

    public record Selection(Request request, long bytes, long sourceFloorBytes, long minimumBytes,
                            long preferredBytes, long availableBytes, Policy policy, Cost cost) {
        public Selection {
            Objects.requireNonNull(request);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(cost);
            if (bytes < UNIT || bytes > policy.maximumBytes() || sourceFloorBytes < 0 || bytes < minimumBytes
                || preferredBytes < minimumBytes || availableBytes < 0)
                throw new IllegalArgumentException("Invalid workspace selection");
        }

        public Map<String, Object> facts() {
            return Map.of("mode", request.mode(), "bytes", bytes, "sourceFloorBytes", sourceFloorBytes,
                "minimumBytes", minimumBytes, "preferredBytes", preferredBytes, "availableBytes", availableBytes,
                "hostReserveBytes", policy.reserveBytes(), "maximumBytes", policy.maximumBytes(), "cost", cost,
                "meaning", "Bounded initial build budget estimate; dependency size and future success are not guaranteed.");
        }
    }

    private DeploymentWorkspaceQuota() {
    }

    /** Keeps an existing physical quota while validating a revised demand, without allocating it again. */
    public static Selection preserve(Input input, Selection current) {
        if (input.request().equals(current.request())) return current;
        long minimum = Math.max(UNIT, roundUp(Math.addExact(input.sourceFloorBytes(), input.request().minimumBuildBytes())));
        if (current.bytes() < minimum || input.request().mode() == Mode.EXPLICIT && input.request().explicitBytes() != current.bytes())
            throw new IllegalArgumentException("Prepared workspace budget cannot change");
        long preferred = Math.max(minimum, roundUp(Math.addExact(input.sourceFloorBytes(), input.request().preferredBuildBytes())));
        return new Selection(input.request(), current.bytes(), input.sourceFloorBytes(), minimum, preferred,
            current.availableBytes(), current.policy(), current.cost());
    }

    public static long sourceFloor(long compressed, long unpacked, int copies) {
        if (compressed < 0 || unpacked < 0 || copies < 1 || copies > 2)
            throw new IllegalArgumentException("WORKSPACE_SOURCE_INVALID");
        return Math.addExact(Math.addExact(compressed, Math.multiplyExact(unpacked, copies)), UNIT);
    }

    public static long hostCost(long compressed, long bytes, Cost cost) {
        if (compressed < 0 || bytes < UNIT || bytes > MAXIMUM)
            throw new IllegalArgumentException("WORKSPACE_BUDGET_INVALID");
        return cost == Cost.AGENT ? Math.multiplyExact(bytes, 2) : Math.max(Math.multiplyExact(compressed, 3), bytes);
    }

    public static Map<String, Selection> select(List<Input> inputs, long available, Policy policy, Cost cost) {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(cost);
        if (available < 0 || inputs.isEmpty()) throw new IllegalArgumentException("WORKSPACE_CAPACITY_UNAVAILABLE");
        var ordered = inputs.stream().sorted(Comparator.comparing(Input::id)).toList();
        var minimum = new LinkedHashMap<String, Long>();
        long reserved = 0;
        for (var input : ordered) {
            long floor = Math.max(UNIT, roundUp(Math.addExact(input.sourceFloorBytes(), input.request().minimumBuildBytes())));
            long bytes = input.request().mode() == Mode.EXPLICIT ? input.request().explicitBytes() : floor;
            if (bytes < floor || bytes > policy.maximumBytes() || minimum.put(input.id(), bytes) != null)
                throw new IllegalArgumentException("WORKSPACE_BUDGET_EXCEEDED: explicit or minimum budget exceeds the admitted bounds");
            reserved = Math.addExact(reserved, hostCost(input.archiveBytes(), bytes, cost));
        }
        long remaining = Math.subtractExact(available, policy.reserveBytes());
        if (remaining < reserved)
            throw new IllegalArgumentException("WORKSPACE_HOST_INSUFFICIENT: availableBytes=" + available
                + " requiredBytes=" + Math.addExact(reserved, policy.reserveBytes()));
        var result = new LinkedHashMap<String, Selection>();
        for (var input : ordered) {
            long min = minimum.get(input.id());
            reserved = Math.subtractExact(reserved, hostCost(input.archiveBytes(), min, cost));
            long preferred = Math.max(min, roundUp(Math.addExact(input.sourceFloorBytes(), input.request().preferredBuildBytes())));
            long bound = Math.subtractExact(remaining, reserved);
            if (cost == Cost.AGENT) bound /= 2;
            long selected = input.request().mode() == Mode.EXPLICIT ? input.request().explicitBytes()
                : Math.min(preferred, Math.min(policy.maximumBytes(), bound / UNIT * UNIT));
            if (selected < min) throw new IllegalArgumentException("WORKSPACE_HOST_INSUFFICIENT");
            result.put(input.id(), new Selection(input.request(), selected, input.sourceFloorBytes(), min, preferred, available, policy, cost));
            remaining = Math.subtractExact(remaining, hostCost(input.archiveBytes(), selected, cost));
        }
        return Collections.unmodifiableMap(result);
    }

    private static long roundUp(long bytes) {
        return Math.multiplyExact(Math.addExact(bytes, UNIT - 1) / UNIT, UNIT);
    }
}
