package com.nantaaditya.example.repository;

import com.nantaaditya.example.helper.OutboundSystemDetailRowMapper;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Low-level {@link DatabaseClient} queries against {@code v_outbound_system_detail}.
 *
 * <p>The view JOINs all five config tables in one round trip, replacing the
 * {@code 1 + N×5} query fan-out that the previous {@code Mono.zip} pattern produced in
 * {@code OutboundServiceImpl}. {@code list()} now issues exactly two queries (count +
 * paged JOIN) regardless of how many systems are on the page.
 */
@Repository
@RequiredArgsConstructor
public class OutboundSystemDetailQuery {

  private static final String VIEW = "v_outbound_system_detail";

  private final DatabaseClient databaseClient;
  private final OutboundSystemDetailRowMapper rowMapper;

  /** Returns the total number of rows in {@code outbound_systems}. */
  public Mono<Long> count() {
    return databaseClient
        .sql("SELECT COUNT(*) FROM outbound_systems")
        .map(row -> row.get(0, Long.class))
        .one();
  }

  /**
   * Returns a page of systems with all their joined config details.
   *
   * @param page zero-based page index
   * @param size maximum number of rows to return
   */
  public Flux<OutboundSystemDetailResponse> findAllPaged(int page, int size) {
    return databaseClient
        .sql("SELECT * FROM " + VIEW + " ORDER BY id LIMIT :limit OFFSET :offset")
        .bind("limit", size)
        .bind("offset", (long) page * size)
        .map(rowMapper::map)
        .all();
  }

  /**
   * Returns the single system matching {@code code}, or an empty {@code Mono} when not found.
   *
   * @param code system code (unique)
   */
  public Mono<OutboundSystemDetailResponse> findByCode(String code) {
    return databaseClient
        .sql("SELECT * FROM " + VIEW + " WHERE code = :code")
        .bind("code", code)
        .map(rowMapper::map)
        .one();
  }
}
