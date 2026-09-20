package gold.debug.windowstolinux.web.api.config;

import java.time.Duration;

public record WebHttpPolicy(int requests, int streams, int uploads, int jsonBytes, int queryCharacters,
                            Duration heartbeat, Duration eventPoll, Duration streamTimeout) {
    public WebHttpPolicy {
        if (requests < 1 || streams < 1 || uploads < 1 || streams + uploads > requests || jsonBytes < 1
                || jsonBytes > 1_048_576 || queryCharacters < 1 || heartbeat == null || heartbeat.isNegative() || heartbeat.isZero()
                || eventPoll == null || eventPoll.isNegative() || eventPoll.isZero() || streamTimeout == null
                || streamTimeout.isNegative()) throw new IllegalArgumentException("Invalid w2l.http limits");
    }
}
