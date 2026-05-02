package com.mbw.account.application.command;

/**
 * Why is the user requesting an SMS verification code? Drives template
 * selection in {@code RequestSmsCodeUseCase} per spec.md FR-009:
 *
 * <ul>
 *   <li>{@link #REGISTER} → unregistered phones get Template A (real
 *       code); registered phones get Template B ("already registered,
 *       please log in"). Same wall-clock latency on both branches.
 *   <li>{@link #LOGIN} → registered phones get Template A; unregistered
 *       phones get Template C ("login attempted on unregistered, please
 *       register first") if approved at Aliyun, otherwise no SMS is
 *       sent and the use case pads to match registered-path latency.
 * </ul>
 */
public enum SmsCodePurpose {
    REGISTER,
    LOGIN
}
