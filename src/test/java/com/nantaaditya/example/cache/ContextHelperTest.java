package com.nantaaditya.example.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.model.constant.CacheType;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.properties.CacheProperties;
import com.nantaaditya.example.properties.embedded.CacheConfiguration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContextHelperTest {

  private ContextHelper contextHelper;

  @Mock
  private CacheProperties cacheProperties;

  @BeforeEach
  void setUp() {
    when(cacheProperties.get(anyString()))
        .thenReturn(new CacheConfiguration(CacheType.TIME, 0, 10, TimeUnit.MINUTES));
    contextHelper = new ContextHelper(cacheProperties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    contextHelper.onStart();
  }

  @Test
  @DisplayName("put and get stores and retrieves ContextDTO by requestId")
  void putAndGet_contextDto_storesAndRetrieves() {
    ContextDTO ctx = contextDto("req-001");

    contextHelper.put(ctx);

    assertThat(contextHelper.get("req-001")).isSameAs(ctx);
  }

  @Test
  @DisplayName("get with null requestId returns null")
  void get_nullRequestId_returnsNull() {
    assertThat(contextHelper.get(null)).isNull();
  }

  @Test
  @DisplayName("get with unknown requestId returns null")
  void get_unknownRequestId_returnsNull() {
    assertThat(contextHelper.get("not-there")).isNull();
  }

  @Test
  @DisplayName("put(requestId, string) and getAdditionalData returns bytes of that string")
  void putAndGetAdditionalData_storesBytes() {
    contextHelper.put("req-002", "{\"error\":\"fail\"}");

    byte[] data = contextHelper.getAdditionalData("req-002");

    assertThat(data).isNotNull();
    assertThat(new String(data)).isEqualTo("{\"error\":\"fail\"}");
  }

  @Test
  @DisplayName("getAdditionalData with null requestId returns null")
  void getAdditionalData_nullRequestId_returnsNull() {
    assertThat(contextHelper.getAdditionalData(null)).isNull();
  }

  @Test
  @DisplayName("getAdditionalData with missing key returns null")
  void getAdditionalData_missingKey_returnsNull() {
    assertThat(contextHelper.getAdditionalData("no-key")).isNull();
  }

  @Test
  @DisplayName("update modifies existing ContextDTO via the provided function")
  void update_existingEntry_appliesFunction() {
    ContextDTO ctx = contextDto("req-003");
    contextHelper.put(ctx);

    contextHelper.update("req-003", c -> {
      c.setResponseCode("000");
      return c;
    });

    assertThat(contextHelper.get("req-003").getResponseCode()).isEqualTo("000");
  }

  @Test
  @DisplayName("update on missing key does nothing and does not throw")
  void update_missingKey_doesNothing() {
    contextHelper.update("missing", c -> c);
  }

  @Test
  @DisplayName("cleanUp invalidates both ContextDTO and additional-context entries")
  void cleanUp_removesAllEntries() {
    contextHelper.put(contextDto("req-004"));
    contextHelper.put("req-004", "extra");

    contextHelper.cleanUp("req-004");

    assertThat(contextHelper.get("req-004")).isNull();
    assertThat(contextHelper.getAdditionalData("req-004")).isNull();
  }

  @Test
  @DisplayName("cleanUp with null requestId does not throw")
  void cleanUp_nullRequestId_doesNotThrow() {
    contextHelper.cleanUp(null);
  }

  @Test
  @DisplayName("size returns the number of stored ContextDTOs")
  void size_afterPuts_returnsCount() {
    contextHelper.put(contextDto("req-005"));
    contextHelper.put(contextDto("req-006"));

    assertThat(contextHelper.size()).isGreaterThanOrEqualTo(2L);
  }

  @Test
  @DisplayName("list returns all stored ContextDTOs")
  void list_afterPuts_returnsAll() {
    contextHelper.put(contextDto("req-007"));
    contextHelper.put(contextDto("req-008"));

    assertThat(contextHelper.list())
        .extracting(ContextDTO::getRequestId)
        .contains("req-007", "req-008");
  }

  private ContextDTO contextDto(String requestId) {
    ContextDTO dto = new ContextDTO();
    dto.setRequestId(requestId);
    return dto;
  }
}
