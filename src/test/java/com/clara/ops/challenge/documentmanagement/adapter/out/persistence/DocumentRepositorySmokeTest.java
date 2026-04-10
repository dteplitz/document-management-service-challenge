package com.clara.ops.challenge.documentmanagement.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Smoke test — Layer 2: Spring Data JPA slice, no web, no MinIO.
 *
 * <p>Validates that: (1) Hibernate can connect to the real Postgres container, (2) ddl-auto:
 * validate accepts the schema created by the init script (entity mapping is correct), (3) save and
 * existsByUserAndName work end-to-end through the JPA layer.
 *
 * <p>Uses the Singleton Pattern — one container per JVM, no per-class lifecycle.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DocumentRepositorySmokeTest {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15")
          .withEnv("TZ", "UTC")
          .withInitScript("test-schema-init.sql");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // TimeZone=UTC is NOT added here — see AbstractIntegrationTest for the rationale.
    registry.add(
        "spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=document_schema");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired DocumentJpaRepository repository;

  @Test
  void saveAndExistsByUserAndName() {
    DocumentEntity doc = new DocumentEntity();
    doc.setUser("smoke-user");
    doc.setName("smoke.pdf");
    doc.setTags(new String[] {"tag1"});
    doc.setMinioPath("smoke/smoke.pdf");
    doc.setFileSize(1024L);
    doc.setFileType("application/pdf");

    repository.save(doc);

    assertThat(repository.existsByUserAndName("smoke-user", "smoke.pdf")).isTrue();
    assertThat(repository.existsByUserAndName("smoke-user", "other.pdf")).isFalse();
  }

  @Test
  void timestampsArePopulatedOnSave() {
    DocumentEntity doc = new DocumentEntity();
    doc.setUser("smoke-user");
    doc.setName("timestamps.pdf");
    doc.setTags(new String[0]);
    doc.setMinioPath("smoke/timestamps.pdf");
    doc.setFileSize(512L);
    doc.setFileType("application/pdf");

    DocumentEntity saved = repository.saveAndFlush(doc);

    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }
}
