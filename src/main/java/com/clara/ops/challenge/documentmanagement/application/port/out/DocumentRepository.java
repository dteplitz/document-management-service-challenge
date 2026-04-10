package com.clara.ops.challenge.documentmanagement.application.port.out;

import com.clara.ops.challenge.documentmanagement.domain.Document;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DocumentRepository {

  boolean existsByUserAndName(String user, String name);

  Document save(Document document);

  Page<Document> search(String user, String name, List<String> tags, Pageable pageable);
}
