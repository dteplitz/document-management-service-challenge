package com.clara.ops.challenge.documentmanagement.adapter.out.storage;

import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentStorage;
import com.clara.ops.challenge.documentmanagement.config.MinioProperties;
import com.clara.ops.challenge.documentmanagement.domain.exception.StorageException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MinioDocumentStorageAdapter implements DocumentStorage {

  // S3 multipart minimum — determines per-upload heap footprint.
  // With Semaphore(3): peak buffer usage = 3 * PART_SIZE = 15 MB.
  // See ADR-006 and ADR-007.
  private static final long PART_SIZE = 5 * 1024 * 1024L;

  private final MinioClient minioClient;
  private final MinioProperties props;

  @Override
  public void store(
      String storagePath, InputStream content, long contentLength, String contentType) {
    try {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(props.bucket()).object(storagePath).stream(
                  content, contentLength, PART_SIZE)
              .contentType(contentType)
              .build());
      log.debug("stored object at {}/{}", props.bucket(), storagePath);
    } catch (Exception e) {
      throw new StorageException("failed to store object at " + storagePath, e);
    }
  }

  @Override
  public void delete(String storagePath) {
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder().bucket(props.bucket()).object(storagePath).build());
      log.debug("deleted object at {}/{}", props.bucket(), storagePath);
    } catch (Exception e) {
      throw new StorageException("failed to delete object at " + storagePath, e);
    }
  }
}
