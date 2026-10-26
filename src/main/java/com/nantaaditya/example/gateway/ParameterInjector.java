package com.nantaaditya.example.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import com.nantaaditya.example.model.dto.ResolvedParameter;
import com.nantaaditya.example.model.exception.InvalidParameterException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Pure injection logic for {@code outbound_parameters}: target validation and JSON body injection.
 * Never mutates its inputs — body injection works on a copy.
 */
@Component
@RequiredArgsConstructor
public class ParameterInjector {

  private static final String FIELD = "injectKeys";
  private static final String TARGET_FIELD = "targetName";
  private static final String VALUE_FIELD = "value";
  private static final String PATH_SEPARATOR = "\\.";

  /** RFC 7230 token characters. */
  private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
  private static final Pattern QUERY_NAME = Pattern.compile("^[A-Za-z0-9_.\\-\\[\\]]+$");
  private static final Pattern BODY_PATH = Pattern.compile("^[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)*$");

  /** Headers owned by the transport or the auth strategy — a parameter must never overwrite them. */
  private static final Set<String> PROTECTED_HEADERS = Set.of(
      "authorization", "proxy-authorization", "cookie", "host", "content-length",
      "content-type", "transfer-encoding", "connection");

  private final ObjectMapper objectMapper;

  public static void validateTarget(ParameterInjectLocation location, String targetName) {
    if (location == null || targetName == null || targetName.isBlank()) {
      throw new InvalidParameterException(TARGET_FIELD, "target name is required");
    }
    switch (location) {
      case HEADER -> validateHeader(targetName);
      case QUERY_PARAM -> requireMatch(QUERY_NAME, targetName, "query param name is not valid");
      case BODY -> requireMatch(BODY_PATH, targetName, "body path is not valid, use dot notation");
    }
  }

  /** Header values must not carry CR/LF or other control characters (header injection / response splitting). */
  public static void validateValue(ParameterInjectLocation location, String value) {
    if (location == ParameterInjectLocation.HEADER && value != null
        && value.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c == 0x7f)) {
      throw new InvalidParameterException(VALUE_FIELD, "header value contains control characters");
    }
  }

  private static void validateHeader(String name) {
    requireMatch(HEADER_NAME, name, "header name is not valid");
    if (PROTECTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
      throw new InvalidParameterException(TARGET_FIELD, "header is protected: " + name);
    }
  }

  private static void requireMatch(Pattern pattern, String value, String message) {
    if (!pattern.matcher(value).matches()) {
      throw new InvalidParameterException(TARGET_FIELD, message);
    }
  }

  /** @return a new {@link JsonNode} with the values injected, or {@code body} itself when there is nothing to inject */
  public Object injectBody(Object body, List<ResolvedParameter> bodyItems) {
    if (bodyItems.isEmpty()) {
      return body;
    }
    if (body == null) {
      throw new InvalidParameterException(FIELD, "request body is required for BODY parameters");
    }
    JsonNode tree = objectMapper.valueToTree(body);
    if (!(tree instanceof ObjectNode root)) {
      throw new InvalidParameterException(FIELD, "request body must be a JSON object for BODY parameters");
    }
    bodyItems.forEach(item -> setAtPath(root, item.targetName(), item.value()));
    return root;
  }

  private void setAtPath(ObjectNode root, String path, String value) {
    String[] segments = path.split(PATH_SEPARATOR);
    ObjectNode current = root;
    for (int i = 0; i < segments.length - 1; i++) {
      JsonNode next = current.get(segments[i]);
      if (next == null || next.isNull()) {
        current = current.putObject(segments[i]);
      } else if (next instanceof ObjectNode objectNode) {
        current = objectNode;
      } else {
        throw new InvalidParameterException(FIELD, "body path segment is not an object: " + segments[i]);
      }
    }
    current.put(segments[segments.length - 1], value);
  }
}
