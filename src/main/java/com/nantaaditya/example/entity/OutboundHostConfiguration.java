package com.nantaaditya.example.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Host-level connection pool configuration (one row per {@code baseUrl}).
 *
 * <p><b>Pool sizing invariants — admin must enforce these:</b>
 * <ul>
 *   <li>{@code maxConnections} MUST be &ge; the SUM of {@code bh_max_concurrent_calls} across
 *       ALL outbound systems sharing this {@code baseUrl}. If the pool is smaller, the pool
 *       becomes the real concurrency limiter and bulkhead metrics are misleading.
 *   <li>{@code pendingAcquireTimeoutMs} MUST be &lt; the MINIMUM {@code read_time_out_ms}
 *       of all systems on this host. If not, a pool-full condition surfaces as a read
 *       timeout (504) instead of a 503, making incident diagnosis much harder.
 * </ul>
 *
 * <p><b>First-wins caveat:</b> All settings here are applied when the pool is first created.
 * If multiple systems share the same {@code baseUrl} with different desired host configs,
 * the first one to initialize wins. Subsequent systems reuse the existing pool unchanged.
 * See {@code GatewayWebClientBuilder} for details.
 */
@Data
@Table(name = "outbound_host_configurations")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("java:S1068")
public class OutboundHostConfiguration extends BaseEntity<Long> {
  private String baseUrl;
  private int connectTimeOutMs;
  private int maxConnections;
  private int pendingAcquireMaxCount;
  private int pendingAcquireTimeoutMs;
  @Column("is_disable_ssl_verification")
  private boolean disableSslVerification;
  @Column("is_use_proxy")
  private boolean useProxy;
  private String proxyHost;
  private int proxyPort;
  private byte[] proxyUsername;
  private byte[] proxyPassword;
}
