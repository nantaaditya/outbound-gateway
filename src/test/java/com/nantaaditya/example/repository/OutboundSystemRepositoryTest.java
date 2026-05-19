package com.nantaaditya.example.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

class OutboundSystemRepositoryTest extends BaseIntegrationTest {

  @Autowired
  private OutboundSystemRepository outboundSystemRepository;

  @AfterEach
  void cleanup() {
    outboundSystemRepository.deleteAll().block();
  }

  @Test
  @DisplayName("save and findByCode returns the saved entity")
  void save_validEntity_foundByCode() {
    OutboundSystem system = OutboundSystem.builder()
        .code("xendit")
        .name("Xendit Payment")
        .description("Xendit payment gateway")
        .httpMethod("POST")
        .baseUrl("https://api.xendit.co")
        .active(true)
        .idempotencyTtlHours(24)
        .build();

    StepVerifier.create(
        outboundSystemRepository.save(system)
            .then(outboundSystemRepository.findByCode("xendit"))
    )
        .assertNext(found -> {
          assertThat(found.getCode()).isEqualTo("xendit");
          assertThat(found.getName()).isEqualTo("Xendit Payment");
          assertThat(found.getBaseUrl()).isEqualTo("https://api.xendit.co");
          assertThat(found.isActive()).isTrue();
          assertThat(found.getIdempotencyTtlHours()).isEqualTo(24);
          assertThat(found.getId()).isNotNull();
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findByCode returns empty when code not found")
  void findByCode_unknownCode_returnsEmpty() {
    StepVerifier.create(outboundSystemRepository.findByCode("unknown"))
        .verifyComplete();
  }

  @Test
  @DisplayName("findByCodeAndActiveTrue returns empty when system is inactive")
  void findByCodeAndActiveTrue_inactiveSystem_returnsEmpty() {
    OutboundSystem system = OutboundSystem.builder()
        .code("inactive-system")
        .name("Inactive")
        .httpMethod("GET")
        .baseUrl("https://example.com")
        .active(false)
        .idempotencyTtlHours(24)
        .build();

    StepVerifier.create(
        outboundSystemRepository.save(system)
            .then(outboundSystemRepository.findByCodeAndActiveTrue("inactive-system"))
    )
        .verifyComplete();
  }
}
