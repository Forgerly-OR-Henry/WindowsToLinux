package gold.debug.windowstolinux.web.secret.crypto;

import gold.debug.windowstolinux.web.secret.masterkey.WebMasterKey;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** Authenticated encryption binds a secret to its workspace, purpose and immutable revision. */
public final class WebSecretCipher {
    private final WebMasterKey key;
    public WebSecretCipher(WebMasterKey key) { this.key = key; }

    public byte[] encrypt(byte[] plaintext, String associated) throws GeneralSecurityException {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, nonce, plaintext, associated);
        return ByteBuffer.allocate(1 + nonce.length + encrypted.length).put((byte) 1).put(nonce).put(encrypted).array();
    }
    public byte[] decrypt(byte[] payload, String associated) throws GeneralSecurityException {
        if (payload.length < 29 || payload[0] != 1) throw new GeneralSecurityException("Credential format is invalid");
        return crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(payload, 1, 13), Arrays.copyOfRange(payload, 13, payload.length), associated);
    }
    private byte[] crypt(int mode, byte[] nonce, byte[] input, String associated) throws GeneralSecurityException {
        byte[] raw = key.copy();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(raw, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } finally { Arrays.fill(raw, (byte) 0); }
    }
}
