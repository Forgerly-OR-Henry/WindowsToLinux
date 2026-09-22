package acceptance.config;

public record FixtureConfiguration(int port, String label) {
    public static FixtureConfiguration load() {
        String raw = System.getenv("PORT");
        if (raw == null || !raw.matches("[0-9]{1,5}"))
            throw new IllegalArgumentException("Invalid PORT");
        int port = Integer.parseInt(raw);
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid PORT");
        String label = System.getenv("FIXTURE_LABEL");
        return new FixtureConfiguration(port,
                mode().equals("config") ? (label == null ? "runtime-config-default" : label) : "deployment-smoke-ok");
    }

    public static String mode() {
        return "smoke";
    }

    public static int status() {
        return 503;
    }
}
