-- Spring Modulith JDBC event publication registry table.
--
-- Why this lives in a Flyway migration (not Modulith's built-in
-- jdbc-schema-initialization):
-- Hibernate's `ddl-auto: validate` runs BEFORE Modulith's lazy schema
-- initializer, so without an explicit pre-existing table the boot fails
-- with `Schema-validation: missing table [event_publication]`.
-- Flyway runs before Hibernate validation, so creating the table here
-- makes the table available by the time validate kicks in.
--
-- Schema mirrors `META-INF/spring-modulith-events-jdbc/schema-postgresql.sql`
-- shipped by spring-modulith-events-jdbc 1.4.x. If we upgrade Modulith
-- across a major (e.g. 2.0), re-verify this DDL against the new
-- bundled schema and add a new V<n>__migrate_modulith_events.sql.
--
-- Table is in the default schema (public) so Modulith's default
-- repository discovery finds it without extra `spring.modulith.events.jdbc.schema`
-- configuration.

CREATE TABLE IF NOT EXISTS public.event_publication (
    id                UUID                    NOT NULL,
    listener_id       TEXT                    NOT NULL,
    event_type        TEXT                    NOT NULL,
    serialized_event  TEXT                    NOT NULL,
    publication_date  TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date   TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON public.event_publication USING hash (serialized_event);

CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON public.event_publication (completion_date);
