package com.nantaaditya.example.configuration;

import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.MavenHelper;
import com.nantaaditya.example.helper.TsidHelper;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.constant.ResponseCode;
import com.nantaaditya.example.model.response.BaseResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.maven.model.Developer;
import org.apache.maven.model.Model;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

@Configuration
public class SwaggerConfiguration {

  @Value("${server.port}")
  private int port;

  @Value("${spring.webflux.base-path}")
  private String contextPath;

  @Bean
  public OpenAPI openApiCustomizer() {
    Model mavenModel = MavenHelper.getMavenModel();

    Info info = new Info()
        .title(mavenModel.getArtifactId())
        .version(mavenModel.getVersion())
        .description(mavenModel.getDescription())
        .license(new License()
            .name("Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International")
            .url("https://creativecommons.org/licenses/by-nc-nd/4.0/")
        );

    for (Developer developer : mavenModel.getDevelopers()) {
      info.setContact(new Contact()
          .name(developer.getName())
          .email(developer.getEmail())
          .url(developer.getUrl()));
    }

    return new OpenAPI()
        .info(info)
        .servers(List.of(createServer()));
  }

  @Bean
  public OperationCustomizer operationCustomizer() {
    Schema defaultResponseSchema = ModelConverters.getInstance()
        .resolveAsResolvedSchema(new AnnotatedType(BaseResponse.class))
        .schema;

    Map<String, Header> defaultHeaders = createDefaultHeaders();

    ApiResponse badRequestResponse = createResponse(
        ResponseCode.BAD_REQUEST.getMessage(),
        defaultResponseSchema,
        BaseResponse.failed(ResponseCode.BAD_REQUEST, Map.of())
    );

    ApiResponse internalErrorResponse = createResponse(
        ResponseCode.INTERNAL_ERROR.getMessage(),
        defaultResponseSchema,
        BaseResponse.failed(ResponseCode.INTERNAL_ERROR, Map.of())
    );

    badRequestResponse.setHeaders(defaultHeaders);
    internalErrorResponse.setHeaders(defaultHeaders);

    return (Operation operation, HandlerMethod handlerMethod) -> {
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DateTimeHelper.ISO_8601_GMT7_FORMAT);

      Parameter clientId = new Parameter()
          .in(ParameterIn.HEADER.toString())
          .schema(new StringSchema())
          .name("x-client-id")
          .description("Unique identifier for every client")
          .required(true)
          .example("default");

      Parameter requestId = new Parameter()
          .in(ParameterIn.HEADER.toString())
          .schema(new StringSchema())
          .name("x-request-id")
          .description("Unique identifier for request")
          .required(true)
          .example(UUID.randomUUID().toString());

      Parameter requestTime = new Parameter()
          .in(ParameterIn.HEADER.toString())
          .schema(new StringSchema())
          .name("x-request-time")
          .description("A request time in ISO8601 format")
          .required(true)
          .example(ZonedDateTime.now().format(formatter));

      operation.addParametersItem(clientId);
      operation.addParametersItem(requestId);
      operation.addParametersItem(requestTime);

      ApiResponses apiResponses = operation.getResponses();

      apiResponses.get("200").setHeaders(defaultHeaders);
      apiResponses.addApiResponse("400", badRequestResponse);
      apiResponses.addApiResponse("500", internalErrorResponse);

      if (handlerMethod.getMethod().getName().equalsIgnoreCase("execute")) {
        Parameter callerServices = new Parameter()
            .in(ParameterIn.HEADER.toString())
            .schema(new StringSchema())
            .name(ConstantName.CALLER_SERVICE_HEADER)
            .description("client caller identifier")
            .required(true)
            .example("Client-A");
        Parameter idempotenceKey = new Parameter()
            .in(ParameterIn.HEADER.toString())
            .schema(new StringSchema())
            .name(ConstantName.IDEMPOTENT_HEADER)
            .description("client caller idempotent key")
            .required(true)
            .example(TsidHelper.generateStringId());

        operation.addParametersItem(callerServices);
        operation.addParametersItem(idempotenceKey);

        Header callerServiceHeader = new Header()
            .schema(new StringSchema())
            .description("client caller identifier")
            .required(true);
        Header idempotenceHeader = new Header()
            .schema(new StringSchema())
            .description("client caller idempotent key")
            .required(true);

        defaultHeaders.put(ConstantName.CALLER_SERVICE_HEADER, callerServiceHeader);
        defaultHeaders.put(ConstantName.IDEMPOTENT_HEADER, idempotenceHeader);
        apiResponses.get("200").setHeaders(defaultHeaders);
      }

      return operation;
    };
  }

  private Server createServer() {
    Server server = new Server();

    StringBuilder sb = new StringBuilder();
    sb.append("http://localhost");
    sb.append(":" + port);
    if (contextPath != null && !contextPath.isEmpty()) {
      sb.append(contextPath);
    }

    server.setUrl(sb.toString());
    server.setDescription("Apps Host");
    return server;
  }

  private Map<String, Header> createDefaultHeaders() {
    Header clientId = new Header()
        .schema(new StringSchema())
        .description("Unique id for client")
        .required(true);

    Header requestId = new Header()
        .schema(new StringSchema())
        .description("Unique id for request")
        .required(true);

    Header requestTime = new Header()
        .schema(new StringSchema())
        .description("A request time in ISO8601 GMT format, " + DateTimeHelper.ISO_8601_GMT7_FORMAT)
        .required(true);

    Header receivedTime = new Header()
        .schema(new StringSchema())
        .description("A receive time in ISO8601 format, " + DateTimeHelper.ISO_8601_GMT7_FORMAT)
        .required(false);

    Header responseTime = new Header()
        .schema(new StringSchema())
        .description("A response time in ISO8601 format, " + DateTimeHelper.ISO_8601_GMT7_FORMAT)
        .required(false);

    Map<String, Header> headers = new HashMap<>();
    headers.put(HeaderConstant.CLIENT_ID.getHeader(), clientId);
    headers.put(HeaderConstant.REQUEST_ID.getHeader(), requestId);
    headers.put(HeaderConstant.REQUEST_TIME.getHeader(), requestTime);
    headers.put(HeaderConstant.RECEIVED_TIME.getHeader(), receivedTime);
    return headers;
  }

  private ApiResponse createResponse(String message, Schema schema, Object example) {
    if (schema == null) {
      return new ApiResponse().description(message);
    }

    MediaType mediaType = new MediaType();
    mediaType.schema(schema);
    mediaType.example(example);

    return new ApiResponse()
        .description(message)
        .content(new Content()
            .addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, mediaType));
  }
}
