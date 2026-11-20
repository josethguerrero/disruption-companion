package com.disruption.companion.config;

import com.disruption.companion.adapters.fixture.ConfigurableRebookingAdapter;
import com.disruption.companion.adapters.fixture.FixtureReadAdapter;
import com.disruption.companion.adapters.fixture.InMemoryHumanCaseAdapter;
import com.disruption.companion.adapters.fixture.InMemoryNotificationAdapter;
import com.disruption.companion.application.EvaluateDisruption;
import com.disruption.companion.application.ExecuteDisruptionAction;
import com.disruption.companion.application.RetryPolicy;
import com.disruption.companion.application.port.HumanCasePort;
import com.disruption.companion.application.port.NotificationPort;
import com.disruption.companion.application.port.RebookingPort;
import com.disruption.companion.domain.Airport;
import com.disruption.companion.domain.ConnectionAnalyzer;
import com.disruption.companion.domain.SourceReconciler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

@Configuration
public class CompanionConfig {

    public static final Map<String, Airport> AIRPORTS = Map.of(
            "SCL", new Airport("SCL", ZoneId.of("America/Santiago"), "CL"),
            "LIM", new Airport("LIM", ZoneId.of("America/Lima"), "PE"),
            "MEX", new Airport("MEX", ZoneId.of("America/Mexico_City"), "MX")
    );

    public static final Duration MINIMUM_CONNECTION = Duration.ofMinutes(60);
    public static final Duration AT_RISK_MARGIN = Duration.ofMinutes(30);

    @Bean
    FixtureReadAdapter fixtureReadAdapter() {
        return new FixtureReadAdapter();
    }

    @Bean
    Clock clock(@Value("${companion.now:}") String now) {
        if (now != null && !now.isBlank()) {
            return Clock.fixed(Instant.parse(now.trim()), ZoneOffset.UTC);
        }
        return Clock.systemUTC();
    }

    @Bean
    EvaluateDisruption evaluateDisruption(FixtureReadAdapter fixtures, Clock clock) {
        ConnectionAnalyzer analyzer = new ConnectionAnalyzer(AIRPORTS, MINIMUM_CONNECTION, AT_RISK_MARGIN);
        return new EvaluateDisruption(
                fixtures, fixtures, fixtures, fixtures,
                SourceReconciler.withDefaults(), analyzer, clock);
    }

    @Bean
    RebookingPort rebookingPort() {
        return new ConfigurableRebookingAdapter();
    }

    @Bean
    HumanCasePort humanCasePort() {
        return new InMemoryHumanCaseAdapter();
    }

    @Bean
    NotificationPort notificationPort() {
        return new InMemoryNotificationAdapter();
    }

    @Bean
    ExecuteDisruptionAction executeDisruptionAction(EvaluateDisruption evaluateDisruption,
                                                    FixtureReadAdapter fixtures,
                                                    RebookingPort rebookingPort,
                                                    HumanCasePort humanCasePort,
                                                    NotificationPort notificationPort,
                                                    Clock clock) {
        return new ExecuteDisruptionAction(
                evaluateDisruption, fixtures, fixtures,
                rebookingPort, humanCasePort, notificationPort,
                clock, RetryPolicy.withDefaults());
    }
}
