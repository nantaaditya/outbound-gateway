package com.nantaaditya.example.configuration;

import com.nantaaditya.example.helper.EncryptionHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityConfiguration {

  @Bean
  public EncryptionHelper encryptionHelper(
      @Value("${gateway.encryption.key}") String base64Key) {
    return new EncryptionHelper(base64Key);
  }
}
