package com.clara.ops.challenge.documentmanagement.adapter.out.persistence;

import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentRepository;
import com.clara.ops.challenge.documentmanagement.domain.Document;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentRepositoryAdapter implements DocumentRepository {

  private final DocumentJpaRepository jpaRepository;

  @Override
  public boolean existsByUserAndName(String user, String name) {
    return jpaRepository.existsByUserAndName(user, name);
  }

  @Override
  public Document save(Document document) {
    DocumentEntity entity = toEntity(document);
    DocumentEntity saved = jpaRepository.save(entity);
    return toDomain(saved);
  }

  private DocumentEntity toEntity(Document document) {
    DocumentEntity entity = new DocumentEntity();
    entity.setUser(document.user());
    entity.setName(document.name());
    entity.setTags(document.tags().toArray(String[]::new));
    entity.setMinioPath(document.storagePath());
    entity.setFileSize(document.fileSize());
    entity.setFileType(document.fileType());
    return entity;
  }

  private Document toDomain(DocumentEntity entity) {
    List<String> tags =
        entity.getTags() != null ? Arrays.asList(entity.getTags()) : List.of();
    return new Document(
        entity.getId(),
        entity.getUser(),
        entity.getName(),
        tags,
        entity.getMinioPath(),
        entity.getFileSize(),
        entity.getFileType(),
        entity.getCreatedAt());
  }
}
