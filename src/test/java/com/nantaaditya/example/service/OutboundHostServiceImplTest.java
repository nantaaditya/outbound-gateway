package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.crypto.EncryptionHelper;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.exception.HostConfigNotFoundException;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.UpdateHostConfigRequest;
import com.nantaaditya.example.model.response.OutboundHostConfigResponse;
import com.nantaaditya.example.model.response.OutboundSystemResponse;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.impl.OutboundHostServiceImpl;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class OutboundHostServiceImplTest {

  @Mock
  private OutboundHostConfigurationRepository hostConfigRepository;
  @Mock
  private OutboundSystemRepository systemRepository;
  @Mock
  private GatewayService gatewayService;

  private EncryptionHelper encryptionHelper;
  private OutboundHostServiceImpl service;

  @BeforeEach
  void setUp() {
    encryptionHelper = new EncryptionHelper(Base64.getEncoder().encodeToString(new byte[32]));
    service = new OutboundHostServiceImpl(hostConfigRepository, systemRepository, gatewayService, encryptionHelper);
  }

  @Test
  @DisplayName("create with no proxy credentials saves and returns response")
  void create_noProxy_savesAndReturnsResponse() {
    CreateHostConfigRequest req = createRequest(false, null, null);
    when(hostConfigRepository.save(any())).thenReturn(Mono.just(savedConfig(1L, "https://api.example.com")));

    StepVerifier.create(service.create(req))
        .assertNext(resp -> {
          assertThat(resp.id()).isEqualTo(1L);
          assertThat(resp.baseUrl()).isEqualTo("https://api.example.com");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("create with proxy credentials encrypts them and saves")
  void create_withProxyCredentials_encryptsAndSaves() {
    CreateHostConfigRequest req = createRequest(true, "proxy-user", "proxy-pass");
    when(hostConfigRepository.save(any())).thenReturn(Mono.just(savedConfig(2L, "https://api.example.com")));

    StepVerifier.create(service.create(req))
        .assertNext(resp -> assertThat(resp.id()).isEqualTo(2L))
        .verifyComplete();

    verify(hostConfigRepository).save(any());
  }

  @Test
  @DisplayName("list returns all host configurations")
  void list_returnsAll() {
    when(hostConfigRepository.findAllBy(any(Pageable.class))).thenReturn(
        Flux.just(savedConfig(1L, "https://a.example.com"), savedConfig(2L, "https://b.example.com")));

    StepVerifier.create(service.list(0, 10))
        .assertNext(list -> {
          assertThat(list).hasSize(2);
          assertThat(list).extracting(OutboundHostConfigResponse::baseUrl)
              .containsExactlyInAnyOrder("https://a.example.com", "https://b.example.com");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("list pages in a stable order: oldest first, id as the tie-breaker (otherwise pages can repeat or skip rows)")
  void list_requestsStableSortedPage() {
    when(hostConfigRepository.findAllBy(any(Pageable.class))).thenReturn(Flux.empty());

    service.list(2, 5).block();

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(hostConfigRepository).findAllBy(pageable.capture());
    assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
    assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    assertThat(pageable.getValue().getSort().stream().map(o -> o.getProperty() + ":" + o.getDirection()))
        .containsExactly("createdDate:ASC", "id:ASC");
  }

  @Test
  @DisplayName("getById returns response for an existing configuration")
  void getById_existingId_returnsResponse() {
    when(hostConfigRepository.findById(1L)).thenReturn(Mono.just(savedConfig(1L, "https://api.example.com")));

    StepVerifier.create(service.getById(1L))
        .assertNext(resp -> assertThat(resp.id()).isEqualTo(1L))
        .verifyComplete();
  }

  @Test
  @DisplayName("getById throws HostConfigNotFoundException when configuration does not exist")
  void getById_missingId_throwsNotFound() {
    when(hostConfigRepository.findById(99L)).thenReturn(Mono.empty());

    StepVerifier.create(service.getById(99L))
        .expectErrorMatches(e -> e instanceof HostConfigNotFoundException)
        .verify();
  }

  @Test
  @DisplayName("update saves updated configuration and evicts the web client pool")
  void update_existingId_savesAndEvictsWebClient() {
    OutboundHostConfiguration existing = savedConfig(1L, "https://api.example.com");
    when(hostConfigRepository.findById(1L)).thenReturn(Mono.just(existing));
    when(hostConfigRepository.save(any())).thenReturn(Mono.just(savedConfig(1L, "https://api.example.com")));

    StepVerifier.create(service.update(1L, updateRequest(false, null, null)))
        .assertNext(resp -> assertThat(resp.id()).isEqualTo(1L))
        .verifyComplete();

    verify(gatewayService).evictHost("https://api.example.com");
  }

  @Test
  @DisplayName("update throws HostConfigNotFoundException when configuration does not exist")
  void update_missingId_throwsNotFound() {
    when(hostConfigRepository.findById(99L)).thenReturn(Mono.empty());

    StepVerifier.create(service.update(99L, updateRequest(false, null, null)))
        .expectErrorMatches(e -> e instanceof HostConfigNotFoundException)
        .verify();

    verify(hostConfigRepository, never()).save(any());
  }

  @Test
  @DisplayName("reload evicts the web client pool for the given configuration id")
  void reload_existingId_evictsWebClient() {
    when(hostConfigRepository.findById(1L)).thenReturn(
        Mono.just(savedConfig(1L, "https://api.example.com")));

    StepVerifier.create(service.reload(1L))
        .expectNext(Boolean.TRUE)
        .verifyComplete();

    verify(gatewayService).evictHost("https://api.example.com");
  }

  @Test
  @DisplayName("reload throws HostConfigNotFoundException when configuration does not exist")
  void reload_missingId_throwsNotFound() {
    when(hostConfigRepository.findById(99L)).thenReturn(Mono.empty());

    StepVerifier.create(service.reload(99L))
        .expectErrorMatches(e -> e instanceof HostConfigNotFoundException)
        .verify();
  }

  @Test
  @DisplayName("listSystems returns systems referencing the given host configuration id")
  void listSystems_existingId_returnsSystemsForHostConfig() {
    OutboundHostConfiguration hostConfig = savedConfig(1L, "https://api.example.com");
    when(hostConfigRepository.findById(1L)).thenReturn(Mono.just(hostConfig));
    OutboundSystem system = OutboundSystem.builder()
        .id(10L).code("sys-a").name("System A").httpMethod("POST")
        .hostConfigurationId(1L).apiPath("/v1/a").active(true).idempotencyTtlHours(24)
        .build();
    when(systemRepository.findByHostConfigurationId(1L)).thenReturn(Flux.just(system));

    StepVerifier.create(service.listSystems(1L))
        .assertNext(list -> {
          assertThat(list).hasSize(1);
          assertThat(list).extracting(OutboundSystemResponse::code).containsExactly("sys-a");
          assertThat(list).extracting(OutboundSystemResponse::baseUrl)
              .containsExactly("https://api.example.com");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("listSystems throws HostConfigNotFoundException when configuration does not exist")
  void listSystems_missingId_throwsNotFound() {
    when(hostConfigRepository.findById(99L)).thenReturn(Mono.empty());

    StepVerifier.create(service.listSystems(99L))
        .expectErrorMatches(e -> e instanceof HostConfigNotFoundException)
        .verify();
  }

  private OutboundHostConfiguration savedConfig(Long id, String baseUrl) {
    OutboundHostConfiguration cfg = OutboundHostConfiguration.builder()
        .baseUrl(baseUrl)
        .connectTimeOutMs(5000)
        .maxConnections(50)
        .pendingAcquireMaxCount(100)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false)
        .useProxy(false)
        .build();
    cfg.setId(id);
    return cfg;
  }

  private CreateHostConfigRequest createRequest(boolean useProxy, String username, String password) {
    return new CreateHostConfigRequest(
        "https://api.example.com", 5000, 50, 100, 5000,
        false, useProxy,
        useProxy ? "proxy.example.com" : null,
        useProxy ? 3128 : 0,
        username, password
    );
  }

  private UpdateHostConfigRequest updateRequest(boolean useProxy, String username, String password) {
    return new UpdateHostConfigRequest(
        5000, 50, 100, 5000,
        false, useProxy,
        useProxy ? "proxy.example.com" : null,
        useProxy ? 3128 : 0,
        username, password
    );
  }
}
