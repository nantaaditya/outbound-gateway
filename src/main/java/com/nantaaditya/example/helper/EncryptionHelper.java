package com.nantaaditya.example.helper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptionHelper {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;

  private final SecretKeySpec secretKey;
  private final SecureRandom secureRandom = new SecureRandom();

  public EncryptionHelper(String base64Key) {
    byte[] keyBytes = Base64.getDecoder().decode(base64Key);
    if (keyBytes.length != 32) {
      throw new IllegalArgumentException("AES-256 key must be 32 bytes");
    }
    this.secretKey = new SecretKeySpec(keyBytes, "AES");
  }

  public byte[] encrypt(byte[] plaintext) {
    try {
      byte[] iv = new byte[IV_LENGTH];
      secureRandom.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext);

      return ByteBuffer.allocate(IV_LENGTH + ciphertext.length)
          .put(iv)
          .put(ciphertext)
          .array();
    } catch (Exception e) {
      throw new RuntimeException("Encryption failed", e);
    }
  }

  public byte[] decrypt(byte[] encryptedData) {
    try {
      ByteBuffer buffer = ByteBuffer.wrap(encryptedData);
      byte[] iv = new byte[IV_LENGTH];
      buffer.get(iv);
      byte[] ciphertext = new byte[buffer.remaining()];
      buffer.get(ciphertext);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      return cipher.doFinal(ciphertext);
    } catch (Exception e) {
      throw new RuntimeException("Decryption failed", e);
    }
  }

  public byte[] encryptString(String plaintext) {
    return encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
  }

  public String decryptToString(byte[] encryptedData) {
    return new String(decrypt(encryptedData), StandardCharsets.UTF_8);
  }
}
