package com.nantaaditya.example.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.model.request.GatewayRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An idempotency key may only ever stand for one request. The fingerprint is what "the same request" means: same
 * query parameters, same body, same parameters to inject, however the client happened to order them.
 */
class RequestFingerprintTest {

  private final RequestFingerprint fingerprint = new RequestFingerprint(new ObjectMapper());

  @Test
  @DisplayName("the fingerprint is a 64 character lower-case hex SHA-256")
  void of_isHexSha256() {
    String value = fingerprint.of(new GatewayRequest(Map.of(), Map.of("amount", 100)));

    assertThat(value).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("the same request gives the same fingerprint every time")
  void of_isDeterministic() {
    GatewayRequest request = new GatewayRequest(Map.of("a", "1"), Map.of("amount", 100), List.of("k1"));

    assertThat(fingerprint.of(request)).isEqualTo(fingerprint.of(request));
  }

  @Test
  @DisplayName("the order of keys in the body does not matter, at any depth")
  void of_bodyKeyOrder_ignored() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("amount", 100);
    first.put("meta", orderedMap("x", 1, "y", 2));
    Map<String, Object> second = new LinkedHashMap<>();
    second.put("meta", orderedMap("y", 2, "x", 1));
    second.put("amount", 100);

    assertThat(fingerprint.of(new GatewayRequest(Map.of(), first)))
        .isEqualTo(fingerprint.of(new GatewayRequest(Map.of(), second)));
  }

  @Test
  @DisplayName("a different body gives a different fingerprint")
  void of_differentBody_differs() {
    assertThat(fingerprint.of(new GatewayRequest(Map.of(), Map.of("amount", 100))))
        .isNotEqualTo(fingerprint.of(new GatewayRequest(Map.of(), Map.of("amount", 101))));
  }

  @Test
  @DisplayName("an array keeps its order: [1,2] is not [2,1]")
  void of_arrayOrder_matters() {
    assertThat(fingerprint.of(new GatewayRequest(Map.of(), Map.of("ids", List.of(1, 2)))))
        .isNotEqualTo(fingerprint.of(new GatewayRequest(Map.of(), Map.of("ids", List.of(2, 1)))));
  }

  @Test
  @DisplayName("different query parameters give a different fingerprint, whatever their order")
  void of_queryParams() {
    Map<String, Object> body = Map.of("a", 1);

    assertThat(fingerprint.of(new GatewayRequest(Map.of("p", "1"), body)))
        .isNotEqualTo(fingerprint.of(new GatewayRequest(Map.of("p", "2"), body)));
    assertThat(fingerprint.of(new GatewayRequest(orderedStringMap("p", "1", "q", "2"), body)))
        .isEqualTo(fingerprint.of(new GatewayRequest(orderedStringMap("q", "2", "p", "1"), body)));
  }

  @Test
  @DisplayName("the keys to inject count, in any order, and none is the same as an empty list")
  void of_injectKeys() {
    Map<String, Object> body = Map.of("a", 1);

    assertThat(fingerprint.of(new GatewayRequest(Map.of(), body, List.of("k1", "k2"))))
        .isEqualTo(fingerprint.of(new GatewayRequest(Map.of(), body, List.of("k2", "k1"))));
    assertThat(fingerprint.of(new GatewayRequest(Map.of(), body, List.of("k1"))))
        .isNotEqualTo(fingerprint.of(new GatewayRequest(Map.of(), body, List.of("k2"))));
    assertThat(fingerprint.of(new GatewayRequest(Map.of(), body, null)))
        .isEqualTo(fingerprint.of(new GatewayRequest(Map.of(), body, List.of())));
  }

  @Test
  @DisplayName("missing query parameters, a missing body and an empty body are all handled")
  void of_nullParts() {
    assertThat(fingerprint.of(new GatewayRequest(null, null)))
        .isEqualTo(fingerprint.of(new GatewayRequest(Map.of(), null)))
        .isNotEqualTo(fingerprint.of(new GatewayRequest(Map.of(), Map.of())));
  }

  @Test
  @DisplayName("a body that is not an object (string, number, array) is fingerprinted too")
  void of_scalarAndArrayBodies() {
    assertThat(fingerprint.of(new GatewayRequest(Map.of(), "text")))
        .isNotEqualTo(fingerprint.of(new GatewayRequest(Map.of(), 42)))
        .isNotEqualTo(fingerprint.of(new GatewayRequest(Map.of(), List.of("text"))));
  }

  private static Map<String, Object> orderedMap(String k1, Object v1, String k2, Object v2) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(k1, v1);
    map.put(k2, v2);
    return map;
  }

  private static Map<String, String> orderedStringMap(String k1, String v1, String k2, String v2) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put(k1, v1);
    map.put(k2, v2);
    return map;
  }
}
