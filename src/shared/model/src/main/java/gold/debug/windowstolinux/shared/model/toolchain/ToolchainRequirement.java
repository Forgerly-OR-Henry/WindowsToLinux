package gold.debug.windowstolinux.shared.model.toolchain;

import java.util.Objects;
import java.util.Optional;

/** Preserves a source declaration even when it has no supported build candidate. / 即使没有构建候选也保留源码声明。 */
public record ToolchainRequirement(ToolchainEcosystemType ecosystem, String declaration, String source,
                                   PurposeType purpose, ConstraintType constraint, Optional<ToolchainVersion> version) {
    public enum PurposeType { BUILD, RUNTIME, LANGUAGE_TARGET }
    public enum ConstraintType { EXACT, SERIES, MINIMUM, UNRESOLVED }

    public ToolchainRequirement {
        ecosystem = Objects.requireNonNull(ecosystem, "ecosystem");
        declaration = bounded(declaration, "declaration");
        source = bounded(source, "source");
        purpose = Objects.requireNonNull(purpose, "purpose");
        constraint = Objects.requireNonNull(constraint, "constraint");
        version = Objects.requireNonNull(version, "version");
        if (version.isPresent() && version.orElseThrow().ecosystem() != ecosystem)
            throw new IllegalArgumentException("version ecosystem mismatch");
        if ((constraint == ConstraintType.UNRESOLVED) != version.isEmpty())
            throw new IllegalArgumentException("unresolved declarations must not manufacture a version");
    }

    public static ToolchainRequirement declared(ToolchainEcosystemType ecosystem, String declaration, String source, PurposeType purpose) {
        String value = Objects.requireNonNull(declaration, "declaration").trim();
        boolean minimum = value.startsWith(">=");
        String token = minimum ? value.substring(2).trim() : value.replaceFirst("^(?:==|=)", "").trim();
        if (!minimum && token.matches("[0-9]+(?:[.][0-9]+)*[.][xX*]")) token = token.substring(0, token.length() - 2);
        Optional<ToolchainVersion> parsed = ToolchainVersion.parse(ecosystem, token);
        ConstraintType kind = parsed.isEmpty() ? ConstraintType.UNRESOLVED : minimum ? ConstraintType.MINIMUM
                : parsed.orElseThrow().numbers().size() <= ecosystem.branchSegments()
                    || ecosystem == ToolchainEcosystemType.DOTNET && parsed.orElseThrow().numbers().size() == 2
                    && parsed.orElseThrow().numbers().get(1) == 0 ? ConstraintType.SERIES : ConstraintType.EXACT;
        return new ToolchainRequirement(ecosystem, declaration, source, purpose, kind, parsed);
    }

    public boolean accepts(ToolchainVersion candidate) {
        if (candidate.ecosystem() != ecosystem || candidate.preview() || version.isEmpty()) return false;
        ToolchainVersion requested = version.orElseThrow();
        return switch (constraint) {
            case EXACT -> requested.sameRelease(candidate);
            case SERIES -> requested.branch().equals(candidate.branch());
            case MINIMUM -> candidate.compareTo(requested) >= 0;
            case UNRESOLVED -> false;
        };
    }

    private static String bounded(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 1024 || value.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("invalid " + field);
        return value;
    }
}
