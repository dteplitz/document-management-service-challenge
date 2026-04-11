package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.ErrorResponse;
import com.clara.ops.challenge.documentmanagement.domain.exception.DocumentNotFoundException;
import com.clara.ops.challenge.documentmanagement.domain.exception.DuplicateDocumentException;
import com.clara.ops.challenge.documentmanagement.domain.exception.InvalidDocumentException;
import com.clara.ops.challenge.documentmanagement.domain.exception.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  @ExceptionHandler(DuplicateDocumentException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ErrorResponse handleDuplicate(DuplicateDocumentException ex) {
    return new ErrorResponse("DUPLICATE_DOCUMENT", ex.getMessage());
  }

  @ExceptionHandler(InvalidDocumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleInvalid(InvalidDocumentException ex) {
    return new ErrorResponse("INVALID_DOCUMENT", ex.getMessage());
  }

  @ExceptionHandler(DocumentNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleNotFound(DocumentNotFoundException ex) {
    return new ErrorResponse("DOCUMENT_NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getAllErrors().stream()
            .map(
                error -> {
                  if (error instanceof FieldError fieldError) {
                    return fieldError.getField() + ": " + fieldError.getDefaultMessage();
                  }
                  return error.getDefaultMessage();
                })
            .findFirst()
            .orElse("validation failed");
    return new ErrorResponse("VALIDATION_FAILED", message);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
  public ErrorResponse handleMaxUploadSize(MaxUploadSizeExceededException ex) {
    return new ErrorResponse("PAYLOAD_TOO_LARGE", "file exceeds the maximum allowed size of 500MB");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleUnreadableMessage(HttpMessageNotReadableException ex) {
    return new ErrorResponse("INVALID_REQUEST", "malformed or unreadable request body");
  }

  @ExceptionHandler(MissingServletRequestPartException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleMissingPart(MissingServletRequestPartException ex) {
    return new ErrorResponse(
        "INVALID_REQUEST", "required request part missing: " + ex.getRequestPartName());
  }

  @ExceptionHandler(MultipartException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleMultipart(MultipartException ex) {
    return new ErrorResponse("INVALID_REQUEST", "malformed multipart request: " + ex.getMessage());
  }

  @ExceptionHandler(StorageException.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ErrorResponse handleStorage(StorageException ex) {
    log.error("storage error", ex);
    return new ErrorResponse("STORAGE_ERROR", "storage operation failed");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
  public ErrorResponse handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
    return new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", ex.getMessage());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleNoResource(NoResourceFoundException ex) {
    return new ErrorResponse("NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ErrorResponse handleUnexpected(Exception ex) {
    log.error("unexpected error", ex);
    return new ErrorResponse("INTERNAL_ERROR", "an unexpected error occurred");
  }
}
