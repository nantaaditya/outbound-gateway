package com.nantaaditya.example.model.dto;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything a {@link com.nantaaditya.example.service.AuthStrategy} may sign: the request as it is about to go on
 * the wire, after configured and injected headers and query values were applied. Credentials produced by the
 * signer are not part of it (a signature cannot cover itself).
 *
 * @param method  HTTP method, upper case
 * @param path    path of the system, without host and without query
 * @param query   client and injected query values, sorted by name; a name may carry several values
 * @param headers configured and injected headers; names are looked up case-insensitively
 * @param body    the exact string that is sent as the body, {@code ""} when there is none
 */
public record SigningInput(
    String method,
    String path,
    Map<String, List<String>> query,
    Map<String, List<String>> headers,
    String body
) {

  public SigningInput {
    query = sorted(query, null);
    headers = sorted(headers, String.CASE_INSENSITIVE_ORDER);
    body = body == null ? "" : body;
  }

  private static Map<String, List<String>> sorted(Map<String, List<String>> source, Comparator<String> order) {
    TreeMap<String, List<String>> copy = new TreeMap<>(order);
    if (source != null) {
      source.forEach((name, values) -> copy.put(name, List.copyOf(values)));
    }
    return Collections.unmodifiableMap(copy);
  }
}
