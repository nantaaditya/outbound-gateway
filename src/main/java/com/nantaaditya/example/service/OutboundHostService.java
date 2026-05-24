package com.nantaaditya.example.service;

import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.UpdateHostConfigRequest;
import com.nantaaditya.example.model.response.OutboundHostConfigResponse;
import java.util.List;
import reactor.core.publisher.Mono;

public interface OutboundHostService {

  Mono<OutboundHostConfigResponse> create(CreateHostConfigRequest request);

  Mono<List<OutboundHostConfigResponse>> list();

  Mono<OutboundHostConfigResponse> getById(long id);

  Mono<OutboundHostConfigResponse> update(long id, UpdateHostConfigRequest request);

  Mono<Boolean> reload(long id);
}
