-- =============================================================================
-- Document Management Service — schema initialization
-- =============================================================================
-- This script is executed once by the Postgres container on first startup
-- (when the data volume is empty). To re-run it during development:
--     docker-compose down -v && docker-compose up --build
--
-- Schema evolution policy: changes to this file are discussed in each slice's
-- deep refinement before being applied. Git history is the schema version log.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Note: the application user (document_user) is created by the companion
-- script 00-create-app-user.sh, which runs before this file. That script
-- reads APP_DB_PASSWORD from the container environment and creates the role.
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- Schema
-- -----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS document_schema AUTHORIZATION document_user;
SET search_path TO document_schema;

-- -----------------------------------------------------------------------------
-- documents table
-- -----------------------------------------------------------------------------
-- Fields mandated by the challenge spec: user, name, tags, minio_path,
-- file_size, file_type, created_at. id + updated_at added as standard
-- engineering practice.
CREATE TABLE documents (
    id          BIGSERIAL   PRIMARY KEY,
    "user"      TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    tags        TEXT[]      NOT NULL DEFAULT ARRAY[]::TEXT[],
    minio_path  TEXT        NOT NULL,
    file_size   BIGINT      NOT NULL,
    file_type   TEXT        NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- ADR-005: duplicates rejected with 409. DB-level enforcement protects
    -- against race conditions between concurrent uploads.
    CONSTRAINT unique_user_name UNIQUE ("user", name)
);

-- -----------------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------------
-- ADR-002: GIN index on tags enables efficient @> (contains) queries.
CREATE INDEX idx_documents_tags_gin ON documents USING GIN (tags);

-- Search endpoint orders by created_at DESC; index supports the ordering.
CREATE INDEX idx_documents_created_at_desc ON documents (created_at DESC);

-- User-scoped searches are common; index supports user filter.
CREATE INDEX idx_documents_user ON documents ("user");

-- -----------------------------------------------------------------------------
-- Grants
-- -----------------------------------------------------------------------------
GRANT USAGE ON SCHEMA document_schema TO document_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA document_schema TO document_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA document_schema TO document_user;
