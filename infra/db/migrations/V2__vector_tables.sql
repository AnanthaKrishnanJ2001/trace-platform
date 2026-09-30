-- pgvector tables backing the Policy Agent and Memory Agent (TDD §4.3).
-- Flyway is run by the orchestrator (TDD §4.1: the database is Spring Boot
-- Core's), but only the FastAPI engine reads/writes these two tables.
--
-- Embedding dimension 768 follows the TDD. The engine must request
-- 768-dimensional vectors from whichever embedding model it uses (the TDD
-- says to match the model the Vertex AI project provisions) — changing the
-- dimension later means a new migration, not an edit to this one.
--
-- Deviation from TDD §4.3 text (flagged, not silently applied): the TDD's
-- lifecycle says the Memory Agent "upserts" an incident on write-back
-- (§2 step 17), but its DDL has no unique key on incident_embeddings for an
-- upsert to target. A retried write-back would otherwise create duplicate
-- vectors for one incident, so incident_id is UNIQUE here.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE policy_chunks (
    id             BIGSERIAL PRIMARY KEY,
    document_name  VARCHAR(150) NOT NULL,
    clause_id      VARCHAR(30),
    chunk_text     TEXT NOT NULL,
    embedding      vector(768) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_policy_chunks_embedding
    ON policy_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE incident_embeddings (
    id            BIGSERIAL PRIMARY KEY,
    incident_id   VARCHAR(20) NOT NULL UNIQUE REFERENCES incidents(incident_id),
    summary_text  TEXT NOT NULL,
    embedding     vector(768) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_incident_embeddings_embedding
    ON incident_embeddings USING hnsw (embedding vector_cosine_ops);
