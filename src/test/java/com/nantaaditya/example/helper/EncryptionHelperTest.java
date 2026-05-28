package com.nantaaditya.example.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EncryptionHelperTest {

  private EncryptionHelper encryptionHelper;

  @BeforeEach
  void setUp() {
    String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
    encryptionHelper = new EncryptionHelper(base64Key);
  }

  @Test
  @DisplayName("encrypt returns non-null byte array different from plaintext")
  void encrypt_plaintext_returnsEncryptedBytes() {
    byte[] plaintext = "secret-api-key".getBytes();

    byte[] encrypted = encryptionHelper.encrypt(plaintext);

    assertThat(encrypted).isNotNull().isNotEqualTo(plaintext);
    assertThat(encrypted.length).isGreaterThan(plaintext.length);
  }

  @Test
  @DisplayName("decrypt returns original plaintext after encrypt-decrypt roundtrip")
  void decrypt_encryptedBytes_returnsOriginalPlaintext() {
    byte[] plaintext = "super-secret-password".getBytes();

    byte[] encrypted = encryptionHelper.encrypt(plaintext);
    byte[] decrypted = encryptionHelper.decrypt(encrypted);

    assertThat(decrypted).isEqualTo(plaintext);
    assertThat(new String(decrypted)).isEqualTo("super-secret-password");
  }

  @Test
  @DisplayName("two encryptions of same plaintext produce different ciphertexts (random IV)")
  void encrypt_samePlaintext_producesDifferentCiphertexts() {
    byte[] plaintext = "same-value".getBytes();

    byte[] first = encryptionHelper.encrypt(plaintext);
    byte[] second = encryptionHelper.encrypt(plaintext);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("decrypt throws on tampered ciphertext")
  void decrypt_tamperedCiphertext_throwsException() {
    byte[] plaintext = "original".getBytes();
    byte[] encrypted = encryptionHelper.encrypt(plaintext);

    // tamper with the ciphertext bytes (after the 12-byte IV)
    encrypted[20] ^= 0xFF;

    assertThatThrownBy(() -> encryptionHelper.decrypt(encrypted))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("encryptString and decryptString roundtrip works correctly")
  void encryptString_decryptString_roundtrip() {
    String original = "client-secret-value";

    byte[] encrypted = encryptionHelper.encryptString(original);
    String decrypted = encryptionHelper.decryptToString(encrypted);

    assertThat(decrypted).isEqualTo(original);
  }

  @Test
  @DisplayName("constructor throws when key is not 32 bytes after Base64 decode")
  void construct_invalidKeyLength_throwsIllegalArgument() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new EncryptionHelper(shortKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");
  }
}
