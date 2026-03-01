package com.nantaaditya.example.entity;

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
  @Id
  T id;
  @CreatedBy
  private String createdBy;
  @LastModifiedBy
  private String updatedBy;
  @CreatedDate
  private long createdDate;
  @LastModifiedDate
  private long updatedDate;
  @Version
  private long version;

  @Override
  @Transient
  public boolean isNew() {
    return createdDate == updatedDate && version == 0;
  }
}
