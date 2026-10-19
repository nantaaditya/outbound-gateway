package com.nantaaditya.example.helper;

import com.nantaaditya.example.entity.OutboundParameter;
import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.dto.ResolvedParameter;
import com.nantaaditya.example.model.dto.ResolvedParameters;
import com.nantaaditya.example.model.exception.InvalidParameterException;
import com.nantaaditya.example.model.exception.ParameterNotFoundException;
import com.nantaaditya.example.model.request.GatewayRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Turns the {@code injectKeys} of a gateway request into decrypted, validated parameters.
 *
 * <p>Looked up per call (only when the client sends keys) through {@link OutboundParameterCache}.
 * Any failure here is a client error and must surface before the resilience
 * pipeline, which would otherwise convert it into an internal reject.
 */
@Component
@RequiredArgsConstructor
public class ParameterResolver {

  private static final String FIELD = "injectKeys";
  static final int MAX_INJECT_KEYS = 50;

  private final OutboundParameterCache parameterCache;
  private final EncryptionHelper encryptionHelper;
  private final ParameterInjector parameterInjector;

  public Mono<ResolvedParameters> resolve(OutboundSystemContext context, GatewayRequest request) {
    if (!request.hasInjectKeys()) {
      return Mono.just(ResolvedParameters.empty());
    }
    Set<String> keys = new LinkedHashSet<>(request.injectKeys());
    if (keys.size() > MAX_INJECT_KEYS) {
      return Mono.error(new InvalidParameterException(FIELD, "too many injectKeys, max " + MAX_INJECT_KEYS));
    }
    long hostId = context.getSystem().getHostConfigurationId();

    return parameterCache.findActive(hostId, keys)
        .collectMap(OutboundParameter::getParamKey, Function.identity())
        .flatMap(found -> decrypt(keys, found))
        .doOnNext(resolved -> validate(context, request, resolved));
  }

  private Mono<ResolvedParameters> decrypt(Set<String> keys, Map<String, OutboundParameter> found) {
    List<String> missing = keys.stream().filter(k -> !found.containsKey(k)).toList();
    if (!missing.isEmpty()) {
      return Mono.error(new ParameterNotFoundException(missing));
    }
    return Mono.fromCallable(() -> keys.stream()
            .map(found::get)
            .map(p -> new ResolvedParameter(p.getParamKey(), p.getInjectLocation(),
                p.getTargetName(), encryptionHelper.decryptToString(p.getParamValue())))
            .collect(Collectors.collectingAndThen(Collectors.toList(), ResolvedParameters::new)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void validate(OutboundSystemContext context, GatewayRequest request, ResolvedParameters resolved) {
    String authKeyName = context.getAuth() == null ? null : context.getAuth().getTokenKeyName();
    for (ResolvedParameter p : resolved.items()) {
      ParameterInjector.validateTarget(p.location(), p.targetName());
      boolean isAuthTarget = authKeyName != null
          && p.location() != ParameterInjectLocation.BODY
          && p.targetName().equalsIgnoreCase(authKeyName);
      if (isAuthTarget) {
        throw new InvalidParameterException(FIELD, "parameter targets the auth key: " + p.key());
      }
    }
    // Dry run: body-shape problems (missing body, array body, path conflict) must surface here as a
    // client error, before the metrics observation starts and outside the resilience pipeline.
    parameterInjector.injectBody(request.body(), resolved.bodyItems());
  }
}
