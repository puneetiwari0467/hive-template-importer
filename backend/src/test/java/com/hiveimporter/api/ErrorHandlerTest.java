package com.hiveimporter.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ErrorHandlerTest {
    @Test
    void unexpectedErrorsLogDiagnosticFramesButNeverUnsafeExceptionMessages(CapturedOutput output) {
        String unsafe = "private-" + UUID.randomUUID();
        var body = ErrorHandler.internalError(new IllegalStateException(unsafe, new RuntimeException(unsafe)));
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).doesNotContain(unsafe);
        assertThat(body.details()).hasSize(1);
        assertThat(output.getAll()).contains("Unexpected server error", "IllegalStateException", "frames=");
        assertThat(output.getAll()).doesNotContain(unsafe);
    }
}
