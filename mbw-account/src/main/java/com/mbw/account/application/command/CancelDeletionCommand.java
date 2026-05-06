package com.mbw.account.application.command;

/**
 * Input to {@code CancelDeletionUseCase}: a public, unauthed request to
 * submit a 6-digit CANCEL_DELETION SMS code, transition FROZEN → ACTIVE,
 * and issue a fresh access / refresh token pair (cancel-deletion spec
 * § Endpoint 2).
 */
public record CancelDeletionCommand(String phone, String code, String clientIp) {}
