package com.nantaaditya.example.entity;

import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.request.admin.UpsertResponseMapperRequest;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table(name = "outbound_response_mapper_configuration")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("java:S1068")
public class OutboundResponseMapperConfiguration extends BaseEntity<Long> {

  private long outboundSystemId;
  private ConverterType converterType;
  private String mappingScript;
  private Map<String, Integer> mappingStatus;
  @Column("is_active")
  private boolean active;

  public boolean isPassThrough() {
    return ConverterType.NONE == converterType;
  }

  public int getMappedStatus(int originalStatus) {
    if (mappingStatus == null || mappingStatus.isEmpty()) {
      return originalStatus;
    }
    return mappingStatus.getOrDefault(String.valueOf(originalStatus), originalStatus);
  }
  
  public OutboundResponseMapperConfiguration updated(long outboundSystemId, UpsertResponseMapperRequest request) {
    return OutboundResponseMapperConfiguration.builder()
        .id(this.getId())
        .createdBy(this.getCreatedBy())
        .createdDate(this.getCreatedDate())
        .version(this.getVersion())
        .outboundSystemId(outboundSystemId)
        .converterType(request.converterType())
        .mappingScript(request.mappingScript())
        .mappingStatus(request.mappingStatus())
        .active(request.active())
        .build();
  }

  public static OutboundResponseMapperConfiguration empty(long systemId) {
    return OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(systemId)
        .converterType(ConverterType.NONE)
        .build();
  }
}
