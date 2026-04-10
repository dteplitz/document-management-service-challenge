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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  protected static final String BUCKET = "document-bucket";

  // Singleton pattern: containers start once per JVM and are shared across all subclasses.
  // @Container/@Testcontainers lifecycle management stops containers after each test class,
  // which breaks the second class when they share static fields via inheritance.
  // Ryuk handles cleanup on JVM exit.
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15")
          .withEnv("TZ", "UTC")
          .withInitScript("test-schema-init.sql");

  static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

  static {
    POSTGRES.start();
    MINIO.start();
  }

  @LocalServerPort protected int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // TimeZone=UTC is NOT added here — the init-script JDBC connection that Testcontainers opens
    // ignores datasource URL params. The actual fix is -Duser.timezone=UTC in surefire argLine.
    registry.add(
        "spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=document_schema");
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
