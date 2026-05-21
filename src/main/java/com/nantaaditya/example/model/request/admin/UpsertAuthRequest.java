package com.nantaaditya.example.model.request.admin;

import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record UpsertAuthRequest(
    @NotNull(message = "NotNull")
    AuthType authType,
    String apiKey,
    String clientId,
    String clientSecret,
    boolean autoRefresh,
    String tokenMethod,
    String tokenUrl,
    String tokenBody,
    String tokenPrefix,
    TokenInjectLocation tokenInjectLocation,
    String tokenKeyName,
    String tokenResponsePath,
    String tokenResponseExpiryPath
) {

  @AssertTrue(message = "NotValid")
  public boolean isAuthFieldsValid() {
    if (authType == null) return true;
    return switch (authType) {
      case API_KEY, HMAC -> hasValue(apiKey);
      case BASIC, RSA -> hasValue(clientId) && hasValue(clientSecret);
      case OAUTH -> hasValue(tokenUrl) && hasValue(tokenMethod) && hasValue(tokenKeyName) && hasValue(tokenResponsePath);
      case BEARER_STATIC -> hasValue(apiKey);
      case NONE -> true;
    };
  }

  private static boolean hasValue(String s) {
    return s != null && !s.isBlank();
  }
}
