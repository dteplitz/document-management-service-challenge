package com.clara.ops.challenge.documentmanagement.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "documents",
    schema = "document_schema",
    uniqueConstraints = @UniqueConstraint(columnNames = {"`user`", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class DocumentEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "`user`", nullable = false)
  private String user;

  @Column(nullable = false)
  private String name;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]", nullable = false)
  private String[] tags;

  @Column(name = "minio_path", nullable = false)
  private String minioPath;

  @Column(name = "file_size", nullable = false)
  private long fileSize;

  @Column(name = "file_type", nullable = false)
  private String fileType;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
