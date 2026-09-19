package com.hiveimporter.service;

import com.hiveimporter.api.ApiException;
import com.hiveimporter.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DemoRateGuard {
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_KEYS = 10_000;
    private final Map<String, ArrayDeque<Instant>> requests = new HashMap<>();
    private final AppProperties properties;
    private final Clock clock;

    public DemoRateGuard(AppProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void workspaceCreation(String remoteAddress) {
        check("workspace:" + remoteAddress, properties.workspaceRequestsPerHour());
    }

    public void importAttempt(String workspaceId) {
        check("import:" + workspaceId, properties.importRequestsPerHour());
    }

    private synchronized void check(String key, int maximum) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        requests.values().forEach(values -> {
            while (!values.isEmpty() && !values.getFirst().isAfter(cutoff)) {
                values.removeFirst();
            }
        });
        requests.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (!requests.containsKey(key) && requests.size() >= MAX_KEYS) {
            throw ApiException.limit("The demo rate guard is at capacity. Please try again later.");
        }
        var recent = requests.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        if (recent.size() >= maximum) {
            throw ApiException.limit("The hourly demo request limit was reached. Please try again in an hour.");
        }
        recent.addLast(now);
    }
}
