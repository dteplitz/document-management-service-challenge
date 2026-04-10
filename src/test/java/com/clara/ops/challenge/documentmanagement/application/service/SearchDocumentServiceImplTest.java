package com.clara.ops.challenge.documentmanagement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clara.ops.challenge.documentmanagement.application.port.in.SearchDocumentQuery;
import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentRepository;
import com.clara.ops.challenge.documentmanagement.domain.Document;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class SearchDocumentServiceImplTest {

  @Mock private DocumentRepository documentRepository;

  @InjectMocks private SearchDocumentServiceImpl service;

  @Test
  void search_noFilters_delegatesNullsToRepo() {
    SearchDocumentQuery query = new SearchDocumentQuery(null, null, null);
    Pageable pageable = defaultPageable();
    when(documentRepository.search(null, null, null, pageable)).thenReturn(Page.empty());

    Page<Document> result = service.search(query, pageable);

    assertThat(result).isEmpty();
    verify(documentRepository).search(null, null, null, pageable);
  }

  @Test
  void search_withUserFilter_passesUserToRepo() {
    SearchDocumentQuery query = new SearchDocumentQuery("alice", null, null);
    Pageable pageable = defaultPageable();
    when(documentRepository.search("alice", null, null, pageable)).thenReturn(Page.empty());

    service.search(query, pageable);

    verify(documentRepository).search("alice", null, null, pageable);
  }

  @Test
  void search_withTagsFilter_passesTagsToRepo() {
    List<String> tags = List.of("finance", "2026");
    SearchDocumentQuery query = new SearchDocumentQuery(null, null, tags);
    Pageable pageable = defaultPageable();
    when(documentRepository.search(null, null, tags, pageable)).thenReturn(Page.empty());

    service.search(query, pageable);

    verify(documentRepository).search(null, null, tags, pageable);
  }

  @Test
  void search_allFilters_passesAllToRepo() {
    List<String> tags = List.of("legal");
    SearchDocumentQuery query = new SearchDocumentQuery("bob", "contract.pdf", tags);
    Pageable pageable = defaultPageable();
    when(documentRepository.search("bob", "contract.pdf", tags, pageable)).thenReturn(Page.empty());

    service.search(query, pageable);

    verify(documentRepository).search("bob", "contract.pdf", tags, pageable);
  }

  @Test
  void search_returnsPageFromRepo() {
    Document doc =
        new Document(
            1L,
            "alice",
            "report.pdf",
            List.of("finance"),
            "alice/report.pdf",
            2048L,
            "application/pdf",
            Instant.now());
    Pageable pageable = defaultPageable();
    Page<Document> repoPage = new PageImpl<>(List.of(doc), pageable, 1);
    when(documentRepository.search(null, null, null, pageable)).thenReturn(repoPage);

    Page<Document> result = service.search(new SearchDocumentQuery(null, null, null), pageable);

    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent().get(0).id()).isEqualTo(1L);
  }

  private Pageable defaultPageable() {
    return PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
  }
}
