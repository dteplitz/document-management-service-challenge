package com.clara.ops.challenge.documentmanagement.domain;

import com.clara.ops.challenge.documentmanagement.domain.exception.InvalidDocumentException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Document(
    Long id,
    String user,
    String name,
    List<String> tags,
    String storagePath,
    long fileSize,
    String fileType,
    Instant createdAt) {

  public Document {
    tags = tags != null ? List.copyOf(tags) : List.of();
  }

  public static Document newUpload(
      String user, String name, List<String> tags, long fileSize, String fileType) {
    validate(user, name, tags, fileSize, fileType);
    return new Document(
        null,
        user,
        name,
        tags,
        user + "/" + UUID.randomUUID() + "/" + name,
        fileSize,
        fileType,
        null);
  }

  private static void validate(
      String user, String name, List<String> tags, long fileSize, String fileType) {
    if (user == null || user.isBlank()) {
      throw new InvalidDocumentException("user is required");
    }
    if (user.contains("/") || user.contains("\\") || user.contains("\0")) {
      throw new InvalidDocumentException("user must not contain path separators or null bytes");
    }
    if (name == null || name.isBlank()) {
      throw new InvalidDocumentException("name is required");
    }
    if (name.contains("/") || name.contains("\\") || name.contains("\0")) {
      throw new InvalidDocumentException("name must not contain path separators or null bytes");
    }
    if (tags == null) {
      throw new InvalidDocumentException("tags must not be null");
    }
    for (String tag : tags) {
      if (tag == null || tag.isBlank()) {
        throw new InvalidDocumentException("tags must not contain blank values");
      }
    }
    if (fileSize <= 0) {
      throw new InvalidDocumentException("file size must be positive");
    }
    if (fileType == null || !"application/pdf".equalsIgnoreCase(fileType)) {
      throw new InvalidDocumentException("file type must be application/pdf");
    }
  }
}
