package com.clara.ops.challenge.documentmanagement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.clara.ops.challenge.documentmanagement.application.port.in.UploadDocumentCommand;
import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentRepository;
import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentStorage;
import com.clara.ops.challenge.documentmanagement.domain.Document;
import com.clara.ops.challenge.documentmanagement.domain.exception.DuplicateDocumentException;
import com.clara.ops.challenge.documentmanagement.domain.exception.StorageException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class UploadDocumentServiceImplTest {

  @Mock private DocumentRepository documentRepository;
  @Mock private DocumentStorage documentStorage;

  private UploadDocumentServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new UploadDocumentServiceImpl(documentRepository, documentStorage, new Semaphore(3));
  }

  @Test
  void upload_happyPath_storesAndPersists() {
    UploadDocumentCommand cmd = command("alice", "report.pdf");
    Document saved =
        new Document(
            1L,
            "alice",
            "report.pdf",
            List.of(),
            "alice/report.pdf",
            1024L,
            "application/pdf",
            Instant.now());
    when(documentRepository.existsByUserAndName("alice", "report.pdf")).thenReturn(false);
    when(documentRepository.save(any())).thenReturn(saved);

    Document result = service.upload(cmd);

    assertThat(result.id()).isEqualTo(1L);
    verify(documentStorage).store(eq("alice/report.pdf"), any(), eq(1024L), eq("application/pdf"));
    verify(documentRepository).save(any());
  }

  @Test
  void upload_duplicatePreCheck_rejectsBeforeStorage() {
    UploadDocumentCommand cmd = command("alice", "report.pdf");
    when(documentRepository.existsByUserAndName("alice", "report.pdf")).thenReturn(true);

    assertThatThrownBy(() -> service.upload(cmd)).isInstanceOf(DuplicateDocumentException.class);

    verifyNoInteractions(documentStorage);
    verify(documentRepository, never()).save(any());
  }

  @Test
  void upload_raceConditionOnSave_compensatesAndThrows() {
    UploadDocumentCommand cmd = command("alice", "report.pdf");
    when(documentRepository.existsByUserAndName("alice", "report.pdf")).thenReturn(false);
    when(documentRepository.save(any())).thenThrow(new DataIntegrityViolationException("unique"));

    assertThatThrownBy(() -> service.upload(cmd)).isInstanceOf(DuplicateDocumentException.class);

    verify(documentStorage).store(anyString(), any(), anyLong(), anyString());
    verify(documentStorage).delete("alice/report.pdf");
  }

  @Test
  void upload_storageFailure_propagatesAndDoesNotPersist() {
    UploadDocumentCommand cmd = command("alice", "report.pdf");
    when(documentRepository.existsByUserAndName("alice", "report.pdf")).thenReturn(false);
    doThrow(new StorageException("minio down", new RuntimeException()))
        .when(documentStorage)
        .store(anyString(), any(), anyLong(), anyString());

    assertThatThrownBy(() -> service.upload(cmd)).isInstanceOf(StorageException.class);

    verify(documentRepository, never()).save(any());
  }

  @Test
  void upload_semaphoreReleasedAfterStorageFailure() {
    Semaphore semaphore = new Semaphore(1);
    service = new UploadDocumentServiceImpl(documentRepository, documentStorage, semaphore);

    UploadDocumentCommand cmd = command("alice", "report.pdf");
    when(documentRepository.existsByUserAndName("alice", "report.pdf")).thenReturn(false);
    doThrow(new StorageException("fail", new RuntimeException()))
        .when(documentStorage)
        .store(anyString(), any(), anyLong(), anyString());

    assertThatThrownBy(() -> service.upload(cmd)).isInstanceOf(StorageException.class);

    assertThat(semaphore.availablePermits()).isEqualTo(1);
  }

  private UploadDocumentCommand command(String user, String name) {
    InputStream in = new ByteArrayInputStream(new byte[1024]);
    return new UploadDocumentCommand(user, name, List.of(), in, 1024L, "application/pdf");
  }
}
