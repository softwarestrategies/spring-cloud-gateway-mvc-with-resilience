package io.softwarestrategies.projectx.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class CircuitBreakerScheduledMonitor {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;

    /**
     * This polls every 30 seconds, checking all registered circuitbreakers and logging about them as appropriate.
     */
    @Scheduled(initialDelay = 10000, fixedRate = 30000) // Run every 30 seconds
    public void monitorCircuitBreakers() {

        if (!circuitBreakerRegistry.getAllCircuitBreakers().isEmpty()) {

            log.info("Status of all registered circuitbreakers:");

            circuitBreakerRegistry.getAllCircuitBreakers().forEach(circuitBreaker -> {
                log.info("  - Circuit breaker '{}' state: {}, metrics: failure rate={}%, slow calls={}%, successful={}, failed={}",
                        circuitBreaker.getName(),
                        circuitBreaker.getState(),
                        circuitBreaker.getMetrics().getFailureRate(),
                        circuitBreaker.getMetrics().getSlowCallRate(),
                        circuitBreaker.getMetrics().getNumberOfSuccessfulCalls(),
                        circuitBreaker.getMetrics().getNumberOfFailedCalls());

                // Register gauge for current state if not already registered
                meterRegistry.gauge("circuitbreaker.state",
                        List.of( Tag.of("name", circuitBreaker.getName()) ),
                        circuitBreaker,
                        cb -> (double) cb.getState().getOrder());
            });
        }
    }
}