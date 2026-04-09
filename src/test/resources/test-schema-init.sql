-- Test schema initialization.
-- Run as the container superuser; no role creation needed.
-- Mirrors 01-schema-init.sql structure without user-specific grants.

CREATE SCHEMA IF NOT EXISTS document_schema;

CREATE TABLE IF NOT EXISTS document_schema.documents
(
    id         BIGSERIAL PRIMARY KEY,
    "user"     TEXT      NOT NULL,
    name       TEXT      NOT NULL,
    tags       TEXT[]    NOT NULL DEFAULT ARRAY []::TEXT[],
    minio_path TEXT      NOT NULL,
    file_size  BIGINT    NOT NULL,
    file_type  TEXT      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_user_name UNIQUE ("user", name)
);

CREATE INDEX IF NOT EXISTS idx_documents_tags_gin ON document_schema.documents USING GIN (tags);
CREATE INDEX IF NOT EXISTS idx_documents_created_at_desc ON document_schema.documents (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_documents_user ON document_schema.documents ("user");
