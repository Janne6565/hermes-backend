package com.janne6565.hermes.services.auth;

import com.janne6565.hermes.configuration.HermesProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-GCM for the stored Google refresh token.
 *
 * <p>The refresh token is an account-level credential with no expiry — a database dump or a Velero
 * snapshot must not be enough to read someone's mail. The key lives in a k8s secret, so restoring a
 * backup into a different cluster yields ciphertext and nothing else.
 *
 * <p>GCM rather than CBC because it authenticates: a tampered ciphertext fails to decrypt instead
 * of silently producing garbage that we would then send to Google.
 */
@Component
public class TokenCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(HermesProperties properties) {
        this.key = new SecretKeySpec(decodeKey(properties.getEncryptionKey()), "AES");
    }

    /**
     * The key is a base64-encoded 32 bytes. It is required — there is deliberately no "generate a
     * random one at startup" fallback, because that would silently make every stored token
     * unreadable after a restart.
     */
    private static byte[] decodeKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "hermes.encryption-key is not set. Generate one with "
                            + "`openssl rand -base64 32` and store it in the hermes-app-key secret.");
        }
        byte[] decoded = Base64.getDecoder().decode(configured.trim());
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "hermes.encryption-key must decode to 32 bytes (AES-256), got "
                            + decoded.length);
        }
        return decoded;
    }

    /**
     * @return base64 of {@code nonce || ciphertext}.
     */
    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            // Deliberately does not include the plaintext or the exception's own message in
            // anything that could reach a log with the token in it.
            throw new IllegalStateException("Failed to encrypt token", exception);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(combined, 0, nonce, 0, NONCE_BYTES);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(combined, NONCE_BYTES, combined.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to decrypt the stored token — the encryption key has probably changed",
                    exception);
        }
    }
}
