package com.clara.ops.challenge.documentmanagement.application.port.in;

import com.clara.ops.challenge.documentmanagement.domain.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SearchDocumentService {

  Page<Document> search(SearchDocumentQuery query, Pageable pageable);
}
