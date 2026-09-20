package gold.debug.windowstolinux.web.secret.masterkey;

import java.util.Arrays;

/** Owns the in-memory key and clears the supplied buffer and the key on close. */
public final class WebMasterKey implements AutoCloseable {
    private final byte[] bytes;
    public WebMasterKey(byte[] supplied) {
        if (supplied == null) throw new IllegalArgumentException("Web master key is missing");
        try {
            if (supplied.length != 32) throw new IllegalArgumentException("Expected a 256-bit Web key");
            bytes = supplied.clone();
        } finally { Arrays.fill(supplied, (byte) 0); }
    }
    public synchronized byte[] copy() {
        if (closed) throw new IllegalStateException("Web master key is closed");
        return bytes.clone();
    }
    private boolean closed;
    @Override public synchronized void close() { Arrays.fill(bytes, (byte) 0); closed = true; }
}
