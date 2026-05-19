package com.nantaaditya.example.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseEntity<T> implements Persistable<T> {

  @Transient
  protected static final String OBJECT_NOT_VALID_ERROR = "object not valid";

  @Id
  T id;
  @CreatedBy
  private String createdBy;
  @LastModifiedBy
  private String updatedBy;
  @CreatedDate
  private LocalDateTime createdDate;
  @LastModifiedDate
  private LocalDateTime updatedDate;
  @Version
  private long version;

  @Override
  @Transient
  public boolean isNew() {
    return id == null;
  }
}
