-- Initial migration for the account module.
--
-- Creates the `account` schema. Concrete tables (accounts, third_party_bindings,
-- account_sessions, etc.) are added in subsequent migrations as use cases land
-- via the SDD four-step workflow.
--
-- Per ADR-0001 (Modular Monolith) + constitution V (Schema Isolation), each
-- business module gets its own PostgreSQL schema; cross-schema FOREIGN KEYS
-- are forbidden — use ID references; cross-module data references via
-- api.service Beans or Spring Modulith events.

CREATE SCHEMA IF NOT EXISTS account;

COMMENT ON SCHEMA account IS 'Account module: user identity, credentials, sessions, lifecycle state machine';
