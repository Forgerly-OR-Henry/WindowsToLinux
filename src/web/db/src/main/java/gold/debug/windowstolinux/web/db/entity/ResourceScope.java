package gold.debug.windowstolinux.web.db.entity;

import java.util.Objects;

/** Server-assigned ownership, never inferred from a browser-supplied ID. */
public record ResourceScope(String workspaceId, String userId) {
    public ResourceScope {
        identifier(workspaceId);
        identifier(userId);
    }

    public static String identifier(String value) {
        if (!Objects.requireNonNull(value).matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Invalid resource identifier");
        return value;
    }
}
