package com.clara.ops.challenge.documentmanagement;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  static final String BUCKET = "document-bucket";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15").withInitScript("test-schema-init.sql");

  @Container
  static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

  @LocalServerPort
  protected int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> POSTGRES.getJdbcUrl() + "?currentSchema=document_schema");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("minio.endpoint", MINIO::getS3URL);
    registry.add("minio.access-key", MINIO::getUserName);
    registry.add("minio.secret-key", MINIO::getPassword);
    registry.add("minio.bucket", () -> BUCKET);
  }

  @BeforeAll
  static void createBucket() throws Exception {
    MinioClient client =
        MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
    if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
      client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }
  }
}
