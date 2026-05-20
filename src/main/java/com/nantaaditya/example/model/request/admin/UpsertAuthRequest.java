package com.nantaaditya.example.model.request.admin;

import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;

public record UpsertAuthRequest(
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
) {}
