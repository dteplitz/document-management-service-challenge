package com.clara.ops.challenge.documentmanagement.domain.exception;

public class DocumentNotFoundException extends DomainException {

  public DocumentNotFoundException(Long id) {
    super(String.format("document with id %d not found", id));
  }
}
