package gold.debug.windowstolinux.shared.model.ecosystem.db.other;

/** Application-owned Redis key prefixes, avoiding unrestricted access to a shared instance. */
public record RedisKeyNamespace(String prefix) {
    public RedisKeyNamespace {
        if (prefix == null || !prefix.matches("[a-z][a-z0-9_-]{0,62}:")) throw new IllegalArgumentException("Redis namespace must be an application prefix ending in a colon");
    }
    public String aclPattern() { return "~" + prefix + "*"; }
}
