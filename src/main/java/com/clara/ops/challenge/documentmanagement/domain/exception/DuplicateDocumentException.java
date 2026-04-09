package com.clara.ops.challenge.documentmanagement.domain.exception;

public class DuplicateDocumentException extends DomainException {

  public DuplicateDocumentException(String user, String name) {
    super(String.format("document '%s' already exists for user '%s'", name, user));
  }
}
