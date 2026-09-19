package com.hiveimporter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hiveimporter.api.ApiModels.WorkspaceCreated;
import com.hiveimporter.config.AppProperties;
import com.hiveimporter.service.DemoRateGuard;
import com.hiveimporter.service.TemplateService;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicControllerTest {
    @Test
    void healthQueriesDatabaseInsteadOfReportingOnlyProcessLiveness() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        var controller = new PublicController(null, null, jdbc, null);
        assertThat(controller.health()).containsEntry("status", "UP");
        verify(jdbc).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void databaseFailureIs503WithoutLeakingConnectionDetails() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("Unsafe internal connection credentials"));
        var controller = new PublicController(null, null, jdbc, null);
        assertThatThrownBy(controller::health).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(error.body().code()).isEqualTo("DATABASE_UNAVAILABLE");
            assertThat(error.body().message()).doesNotContain("Unsafe", "credentials");
        });
    }

    @Test
    void workspaceRateGuardUsesTheSocketAddressAndDoesNotTrustForwardedHeaders() {
        var service = mock(TemplateService.class);
        when(service.createWorkspace()).thenReturn(new WorkspaceCreated("test-token", "test-workspace", "test-template"));
        var guard = new DemoRateGuard(
                new AppProperties(List.of("http://localhost:5173"), 100, 10, 25000, 1, 20), Clock.systemUTC());
        var controller = new PublicController(service, null, null, guard);
        var first = new MockHttpServletRequest();
        first.setRemoteAddr("127.0.0.1");
        first.addHeader("X-Forwarded-For", "192.0.2.1");
        assertThat(controller.createWorkspace(first).getStatusCode().value()).isEqualTo(201);
        var second = new MockHttpServletRequest();
        second.setRemoteAddr("127.0.0.1");
        second.addHeader("X-Forwarded-For", "192.0.2.2");
        assertThatThrownBy(() -> controller.createWorkspace(second)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(service).createWorkspace();
    }
}
