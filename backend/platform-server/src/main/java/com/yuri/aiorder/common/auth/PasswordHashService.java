package com.yuri.aiorder.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordHashService {

    private static final String FORMAT = "pbkdf2_sha256";
    private static final int KEY_LENGTH_BITS = 256;
    private static final int DEFAULT_ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private final SecureRandom secureRandom = new SecureRandom();

    public String hash(String rawPassword) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            secureRandom.nextBytes(salt);
            byte[] encoded = pbkdf2(rawPassword, salt, DEFAULT_ITERATIONS);
            return FORMAT + "$"
                    + DEFAULT_ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(encoded);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "cannot hash password", ex);
        }
    }

    public boolean matches(String rawPassword, String storedHash) {
        String[] parts = storedHash == null ? new String[0] : storedHash.split("\\$", -1);
        if (parts.length != 4 || !FORMAT.equals(parts[0])) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "unsupported password hash format");
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid password hash", ex);
        }
    }

    private byte[] pbkdf2(String rawPassword, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(
                rawPassword.toCharArray(),
                salt,
                iterations,
                KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            byte[] passwordBytes = rawPassword.getBytes(StandardCharsets.UTF_8);
            spec.clearPassword();
            java.util.Arrays.fill(passwordBytes, (byte) 0);
        }
    }
}
