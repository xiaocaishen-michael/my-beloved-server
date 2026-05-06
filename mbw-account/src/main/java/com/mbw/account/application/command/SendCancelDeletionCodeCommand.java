package com.mbw.account.application.command;

/**
 * Input to {@code SendCancelDeletionCodeUseCase}: a public, unauthed
 * request to send a CANCEL_DELETION SMS code to a phone number whose
 * account is FROZEN within the 15-day grace window.
 *
 * <p>Phone is supplied directly (not derived from an authenticated
 * principal) because the client side has no access token at this point
 * — cancel-deletion is the entry path back into the account before
 * tokens can be re-issued (cancel-deletion spec § Endpoint 1).
 */
public record SendCancelDeletionCodeCommand(String phone, String clientIp) {}
