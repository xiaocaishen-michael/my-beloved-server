package com.mbw.shared.api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compact-constructor invariants for {@link EmailMessage}.
 *
 * <p>The record's compact ctor is the only behavior in {@code mbw-shared}'s
 * email package — interface ({@link EmailSender}) and exception
 * ({@link EmailSendException}) carry no logic worth testing. Coverage on
 * the four field-validation branches (null + blank) keeps the shared
 * module's JaCoCo branch-coverage gate ≥ 0.50.
 */
class EmailMessageTest {

    @Test
    @DisplayName("normal payload constructs cleanly")
    void valid_payload_constructs() {
        EmailMessage msg = new EmailMessage("from@x.com", "to@y.com", "Hi", "Body");
        assertThat(msg.from()).isEqualTo("from@x.com");
        assertThat(msg.to()).isEqualTo("to@y.com");
        assertThat(msg.subject()).isEqualTo("Hi");
        assertThat(msg.text()).isEqualTo("Body");
    }

    @Test
    @DisplayName("null from rejected")
    void null_from_rejected() {
        assertThatThrownBy(() -> new EmailMessage(null, "to@y.com", "s", "t"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("from");
    }

    @Test
    @DisplayName("null to rejected")
    void null_to_rejected() {
        assertThatThrownBy(() -> new EmailMessage("f@x.com", null, "s", "t"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("to");
    }

    @Test
    @DisplayName("null subject rejected")
    void null_subject_rejected() {
        assertThatThrownBy(() -> new EmailMessage("f@x.com", "t@y.com", null, "t"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("subject");
    }

    @Test
    @DisplayName("null text rejected")
    void null_text_rejected() {
        assertThatThrownBy(() -> new EmailMessage("f@x.com", "t@y.com", "s", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("text");
    }

    @Test
    @DisplayName("blank from rejected")
    void blank_from_rejected() {
        assertThatThrownBy(() -> new EmailMessage("   ", "to@y.com", "s", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("blank to rejected")
    void blank_to_rejected() {
        assertThatThrownBy(() -> new EmailMessage("f@x.com", "", "s", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("blank subject rejected")
    void blank_subject_rejected() {
        assertThatThrownBy(() -> new EmailMessage("f@x.com", "t@y.com", " ", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("blank text rejected")
    void blank_text_rejected() {
        assertThatThrownBy(() -> new EmailMessage("f@x.com", "t@y.com", "s", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}
