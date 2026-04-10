package com.clara.ops.challenge.documentmanagement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentRepository;
import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentStorage;
import com.clara.ops.challenge.documentmanagement.domain.Document;
import com.clara.ops.challenge.documentmanagement.domain.exception.DocumentNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DownloadDocumentServiceImplTest {

  @Mock private DocumentRepository documentRepository;
  @Mock private DocumentStorage documentStorage;

  @InjectMocks private DownloadDocumentServiceImpl service;

  @Test
  void getDownloadUrl_existingDocument_returnsPresignedUrl() {
    Document doc = document(1L, "alice/report.pdf");
    when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
    when(documentStorage.generatePresignedUrl("alice/report.pdf"))
        .thenReturn("https://minio/signed-url");

    String url = service.getDownloadUrl(1L);

    assertThat(url).isEqualTo("https://minio/signed-url");
    verify(documentStorage).generatePresignedUrl("alice/report.pdf");
  }

  @Test
  void getDownloadUrl_documentNotFound_throwsDocumentNotFoundException() {
    when(documentRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getDownloadUrl(99L))
        .isInstanceOf(DocumentNotFoundException.class)
        .hasMessageContaining("99");

    verifyNoInteractions(documentStorage);
  }

  private Document document(Long id, String storagePath) {
    return new Document(
        id, "alice", "report.pdf", List.of(), storagePath, 1024L, "application/pdf", Instant.now());
  }
}
