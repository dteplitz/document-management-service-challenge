package com.clara.ops.challenge.documentmanagement.adapter.out.storage;

import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentStorage;
import com.clara.ops.challenge.documentmanagement.config.MinioProperties;
import com.clara.ops.challenge.documentmanagement.domain.exception.StorageException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MinioDocumentStorageAdapter implements DocumentStorage {

  // S3 multipart minimum — determines per-upload heap footprint.
  // With Semaphore(3): peak buffer usage = 3 * PART_SIZE = 15 MB.
  // See ADR-006 and ADR-007.
  private static final long PART_SIZE = 5 * 1024 * 1024L;

  private final MinioClient minioClient;
  private final MinioClient presignedMinioClient;
  private final MinioProperties props;

  public MinioDocumentStorageAdapter(
      MinioClient minioClient,
      @Qualifier("presignedMinioClient") MinioClient presignedMinioClient,
      MinioProperties props) {
    this.minioClient = minioClient;
    this.presignedMinioClient = presignedMinioClient;
    this.props = props;
  }

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
  public String generatePresignedUrl(String storagePath) {
    try {
      // Uses the presigned client (configured with the public endpoint) so the
      // Host header in the AWS Signature V4 matches what external callers resolve.
      return presignedMinioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.GET)
              .bucket(props.bucket())
              .object(storagePath)
              .region(props.region())
              .expiry(props.presignedUrlExpirySeconds(), TimeUnit.SECONDS)
              .build());
    } catch (Exception e) {
      throw new StorageException("failed to generate pre-signed URL for " + storagePath, e);
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
