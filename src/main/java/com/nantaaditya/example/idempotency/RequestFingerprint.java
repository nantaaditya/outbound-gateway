package com.nantaaditya.example.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nantaaditya.example.model.request.GatewayRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * What "the same request" means for an idempotency key: the same query parameters, the same body and the same
 * parameters to inject. A key reused with a request that fingerprints differently is a client mistake, not a retry,
 * and must not be answered with the stored result of the first one.
 *
 * <p>The fingerprint is the SHA-256 of a canonical JSON form: object keys sorted at every depth, so the order the
 * client happened to send them in does not matter; array order is kept, because it can change the meaning; the keys
 * to inject are sorted and de-duplicated, because their order does not change what is injected. Missing query
 * parameters and missing injection keys count as empty. It is a hash of the client's own request, stored next to
 * the history row, so it adds nothing a reader of that row could not already see.
 */
@Component
public class RequestFingerprint {

  private final ObjectMapper canonicalMapper;

  public RequestFingerprint(ObjectMapper objectMapper) {
    this.canonicalMapper = objectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  public String of(GatewayRequest request) {
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("query", Objects.requireNonNullElse(request.queryParams(), Map.of()));
    canonical.put("body", request.body());
    canonical.put("inject", request.injectKeys() == null
        ? List.of()
        : request.injectKeys().stream().distinct().sorted().toList());
    try {
      return sha256Hex(canonicalMapper.writeValueAsString(canonical));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("request cannot be fingerprinted", e);
    }
  }

  private static String sha256Hex(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
