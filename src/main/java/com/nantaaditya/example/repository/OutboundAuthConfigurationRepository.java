package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import java.time.LocalDateTime;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundAuthConfigurationRepository
    extends ReactiveCrudRepository<OutboundAuthConfiguration, Long> {

  Mono<OutboundAuthConfiguration> findByOutboundSystemId(long outboundSystemId);

  Mono<Void> deleteByOutboundSystemId(long outboundSystemId);

  @Modifying
  @Query("UPDATE outbound_auth_configurations SET current_token = :token, token_expiry_time = :expiry WHERE id = :id AND outbound_system_id = :outboundSystemId")
  Mono<Integer> updateToken(
      @Param("id") Long id,
      @Param("outboundSystemId") long outboundSystemId,
      @Param("token") String token,
      @Param("expiry") LocalDateTime expiry);
}
