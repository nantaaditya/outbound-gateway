package com.nantaaditya.example.service;

import com.nantaaditya.example.model.request.admin.UpsertParametersRequest;
import com.nantaaditya.example.model.response.OutboundParameterResponse;
import java.util.List;
import reactor.core.publisher.Mono;

public interface OutboundParameterService {

  Mono<List<OutboundParameterResponse>> upsert(long hostId, UpsertParametersRequest request);

  Mono<List<OutboundParameterResponse>> list(long hostId);

  Mono<Boolean> delete(long hostId, String key);
}
