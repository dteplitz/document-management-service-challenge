package com.clara.ops.challenge.documentmanagement.application.port.out;

import com.clara.ops.challenge.documentmanagement.domain.Document;

public interface DocumentRepository {

  boolean existsByUserAndName(String user, String name);

  Document save(Document document);
}
