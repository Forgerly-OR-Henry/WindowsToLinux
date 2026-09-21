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

/**
 * Binds authenticated encryption to a secret's workspace, purpose and immutable revision.
 * <p>将认证加密绑定到秘密的工作区、用途及不可变修订。
 */
public final class WebSecretCipher {
    /**
     * Lookup key within the current contract.
     * <p>当前契约内的查找键。
     */
    private final WebMasterKey key;
    /**
     * Binds the supplied dependencies and state for web secret cipher.
     * <p>为Web秘密密码器绑定传入的依赖及状态。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     */
    public WebSecretCipher(WebMasterKey key) { this.key = key; }

    /**
     * Encrypts secret content with authenticated protection under the selected key contract.
     * <p>按所选密钥契约对秘密内容进行认证加密保护。
     *
     * @param plaintext plaintext / 明文
     * @param associated associated / 关联
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws GeneralSecurityException if the requested cryptographic primitive or key cannot be used / 无法使用请求的加密原语或密钥时
     */
    public byte[] encrypt(byte[] plaintext, String associated) throws GeneralSecurityException {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, nonce, plaintext, associated);
        return ByteBuffer.allocate(1 + nonce.length + encrypted.length).put((byte) 1).put(nonce).put(encrypted).array();
    }
    /**
     * Authenticates encrypted content before returning decrypted secret material.
     * <p>在返回解密秘密素材前认证加密内容。
     *
     * @param payload payload / 载荷
     * @param associated associated / 关联
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws GeneralSecurityException if the requested cryptographic primitive or key cannot be used / 无法使用请求的加密原语或密钥时
     */
    public byte[] decrypt(byte[] payload, String associated) throws GeneralSecurityException {
        if (payload.length < 29 || payload[0] != 1) throw new GeneralSecurityException("Credential format is invalid");
        return crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(payload, 1, 13), Arrays.copyOfRange(payload, 13, payload.length), associated);
    }
    /**
     * Performs AES-GCM encryption or decryption with authenticated context and clears the temporary key copy.
     * <p>结合认证上下文执行 AES-GCM 加密或解密，并清空临时密钥副本。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param nonce nonce / 随机数
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param associated associated / 关联
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws GeneralSecurityException if the requested cryptographic primitive or key cannot be used / 无法使用请求的加密原语或密钥时
     */
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
