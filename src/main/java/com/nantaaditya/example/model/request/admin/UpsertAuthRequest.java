package com.nantaaditya.example.model.request.admin;

import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

@Schema(
    allowableValues = {
        "API_KEY -> apiKey should not be blank",
        "HMAC, BEARER_STATIC -> clientSecret should not be blank",
        "HMAC_TIMESTAMP -> clientSecret, tokenKeyName (signature header), authSettings.timestampHeader should not be blank",
        "BASIC, RSA -> clientId, clientSecret should not be blank",
        "OAUTH -> tokenUrl, tokenMethod, tokenKeyName, tokenResponsePath should not be blank"
    }
)
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
    @Schema(
        description = "json path of token value, ex: $.token"
    )
    String tokenResponsePath,
    @Schema(
        description = "json path of token expiry time, ex: $.expiryTime"
    )
    String tokenResponseExpiryPath,
    @Schema(
        description = "non-secret settings of the authentication type, ex: {\"timestampHeader\": \"X-Timestamp\"} for HMAC_TIMESTAMP"
    )
    Map<String, String> authSettings
) {

  public UpsertAuthRequest(AuthType authType, String apiKey, String clientId, String clientSecret,
      boolean autoRefresh, String tokenMethod, String tokenUrl, String tokenBody, String tokenPrefix,
      TokenInjectLocation tokenInjectLocation, String tokenKeyName, String tokenResponsePath,
      String tokenResponseExpiryPath) {
    this(authType, apiKey, clientId, clientSecret, autoRefresh, tokenMethod, tokenUrl, tokenBody, tokenPrefix,
        tokenInjectLocation, tokenKeyName, tokenResponsePath, tokenResponseExpiryPath, null);
  }

  @AssertTrue(message = "NotValid")
  public boolean isAuthFieldsValid() {
    if (authType == null) return true;
    return switch (authType) {
      case API_KEY -> hasValue(apiKey);
      case HMAC, BEARER_STATIC -> hasValue(clientSecret);
      case HMAC_TIMESTAMP -> hasValue(clientSecret) && hasValue(tokenKeyName)
          && authSettings != null && hasValue(authSettings.get("timestampHeader"));
      case BASIC, RSA -> hasValue(clientId) && hasValue(clientSecret);
      case OAUTH -> hasValue(tokenUrl) && hasValue(tokenMethod) && hasValue(tokenKeyName) && hasValue(tokenResponsePath);
      case NONE -> true;
    };
  }

  private static boolean hasValue(String s) {
    return s != null && !s.isBlank();
  }
}
