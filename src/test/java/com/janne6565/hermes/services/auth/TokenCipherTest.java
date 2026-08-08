package com.janne6565.hermes.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.janne6565.hermes.configuration.HermesProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The refresh token is an account-level credential, so the crypto around it gets real tests. */
class TokenCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static TokenCipher cipherWithKey(String key) {
        HermesProperties properties = new HermesProperties();
        properties.setEncryptionKey(key);
        return new TokenCipher(properties);
    }

    @Test
    void roundTripsAToken() {
        TokenCipher cipher = cipherWithKey(KEY);
        String token = "1//0gExampleRefreshToken_with-symbols.and~stuff";
        assertThat(cipher.decrypt(cipher.encrypt(token))).isEqualTo(token);
    }

    @Test
    void producesADifferentCiphertextEachTime() {
        // A fresh nonce per encryption: identical plaintexts must not be linkable in the database.
        TokenCipher cipher = cipherWithKey(KEY);
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        TokenCipher cipher = cipherWithKey(KEY);
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("token"));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        // GCM authenticates — a flipped bit must fail loudly, not decrypt to garbage we would
        // then send to Google as a refresh token.
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAKey() {
        // No random-key fallback: that would silently orphan every stored token on restart.
        assertThatThrownBy(() -> cipherWithKey(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hermes.encryption-key");
    }

    @Test
    void refusesAWrongLengthKey() {
        assertThatThrownBy(() -> cipherWithKey(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
