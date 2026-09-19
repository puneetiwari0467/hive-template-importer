package com.hiveimporter.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hiveimporter.api.ApiException;
import com.hiveimporter.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoRateGuardTest {
    @Test
    void windowsExpireAndWorkspaceImportsHaveIndependentBoundedBudgets() {
        var clock = new AdjustableClock();
        var properties = new AppProperties(List.of("http://localhost:5173"), 100, 10, 25000, 2, 1);
        var guard = new DemoRateGuard(properties, clock);
        guard.workspaceCreation("127.0.0.1");
        guard.workspaceCreation("127.0.0.1");
        assertThatThrownBy(() -> guard.workspaceCreation("127.0.0.1")).isInstanceOf(ApiException.class)
                .hasMessageContaining("hourly");
        assertThatCode(() -> guard.workspaceCreation("127.0.0.2")).doesNotThrowAnyException();
        guard.importAttempt("workspace-one");
        assertThatThrownBy(() -> guard.importAttempt("workspace-one")).isInstanceOf(ApiException.class);
        assertThatCode(() -> guard.importAttempt("workspace-two")).doesNotThrowAnyException();
        clock.instant = clock.instant.plusSeconds(3600);
        assertThatCode(() -> guard.workspaceCreation("127.0.0.1")).doesNotThrowAnyException();
        assertThatCode(() -> guard.importAttempt("workspace-one")).doesNotThrowAnyException();
    }

    private static final class AdjustableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
