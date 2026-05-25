package com.nantaaditya.example.service;

import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpdateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import com.nantaaditya.example.model.request.admin.UpsertHeadersRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest;
import com.nantaaditya.example.model.request.admin.UpsertResponseMapperRequest;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse;
import com.nantaaditya.example.model.response.OutboundSystemResponse;
import com.nantaaditya.example.model.response.PageResponse;
import com.nantaaditya.example.model.response.TestConnectionResponse;
import reactor.core.publisher.Mono;

public interface OutboundService {

  Mono<OutboundSystemResponse> create(CreateOutboundSystemRequest request);

  Mono<PageResponse<OutboundSystemDetailResponse>> list(int page, int size);

  Mono<OutboundSystemDetailResponse> getDetail(String code);

  Mono<OutboundSystemResponse> update(String code, UpdateOutboundSystemRequest request);

  Mono<Boolean> delete(String code);

  Mono<Boolean> upsertResilience(String code, UpsertResilienceRequest request);

  Mono<Boolean> upsertHeaders(String code, UpsertHeadersRequest request);

  Mono<Boolean> upsertAuth(String code, UpsertAuthRequest request);

  Mono<Boolean> upsertResponseMapper(String code, UpsertResponseMapperRequest request);

  Mono<Boolean> reload(String code);

  Mono<TestConnectionResponse> testConnection(String code);
}
