package com.clara.ops.challenge.documentmanagement.application.service;

import com.clara.ops.challenge.documentmanagement.application.port.in.SearchDocumentQuery;
import com.clara.ops.challenge.documentmanagement.application.port.in.SearchDocumentService;
import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentRepository;
import com.clara.ops.challenge.documentmanagement.domain.Document;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchDocumentServiceImpl implements SearchDocumentService {

  private final DocumentRepository documentRepository;

  @Override
  public Page<Document> search(SearchDocumentQuery query, Pageable pageable) {
    return documentRepository.search(query.user(), query.name(), query.tags(), pageable);
  }
}
