package com.mbw.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    private static final String VALID_SECRET = "test-secret-with-at-least-32-bytes-of-entropy-please";

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_accept_valid_secret() {
        assertThatCode(() -> new JwtProperties(VALID_SECRET)).doesNotThrowAnyException();
    }

    @Test
    void should_reject_secret_below_32_bytes_in_compact_constructor() {
        assertThatThrownBy(() -> new JwtProperties("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void should_reject_secret_at_31_bytes_boundary() {
        String thirtyOneBytes = "a".repeat(31);

        assertThatThrownBy(() -> new JwtProperties(thirtyOneBytes)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_accept_secret_at_exactly_32_bytes() {
        String thirtyTwoBytes = "a".repeat(32);

        assertThatCode(() -> new JwtProperties(thirtyTwoBytes)).doesNotThrowAnyException();
    }

    @Test
    void NotBlank_validator_should_flag_null_secret() {
        // The compact ctor allows null (length check guarded behind != null) so
        // Spring's @Validated layer is the fail-fast gate at boot — exercise
        // that path here via the standalone Validator API.
        JwtProperties propsWithNull = new JwtProperties(null);

        Set<ConstraintViolation<JwtProperties>> violations = validator.validate(propsWithNull);

        assertThat(violations)
                .as("@NotBlank should report a violation for the null secret field")
                .anyMatch(v -> v.getPropertyPath().toString().equals("secret"));
    }
}
