package io.softwarestrategies.projectx.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class ResilienceConfig {

    public final static String ROUTE_WIDGET_SERVICE = "widget-service";

    private long HTTP_INITIAL_INTERVAL = 1000;
    private int HTTP_MAX_ATTEMPTS = 5;

    public static final Double RANDOMIZATION_FACTOR = 0.2;  // 20% jitter
    public static final Double MULTIPLIER = 1.5;

    private final MeterRegistry meterRegistry;

    /**
     *
     * @return
     */
    @Bean
    public RetryRegistry retryRegistry() {
        IntervalFunction httpExponentialRandomBackoffIntervalFunction =
                IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(HTTP_INITIAL_INTERVAL), MULTIPLIER, RANDOMIZATION_FACTOR);

        RetryConfig httpClientJitterRetry = RetryConfig.custom()
                .maxAttempts(HTTP_MAX_ATTEMPTS)
                .intervalFunction(httpExponentialRandomBackoffIntervalFunction)
                .retryExceptions(IOException.class, HttpServerErrorException.class, TimeoutException.class,
                        ResourceAccessException.class, ConnectException.class)
                .retryOnException(throwable -> {
                    if (throwable instanceof HttpClientErrorException ex) {
                        if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS ||
                                ex.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE ||
                                ex.getStatusCode() == HttpStatus.GATEWAY_TIMEOUT) {
                            return true;
                        }
                        return false;
                    }
                    return throwable instanceof IOException || throwable instanceof TimeoutException;
                })
                .build();

        RetryRegistry registry = RetryRegistry.ofDefaults();
        registry.retry("httpClientJitterRetry", httpClientJitterRetry);

        return registry;
    }

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer() {
        CircuitBreakerConfig defaultCircuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20) // Increased sliding window size
                .permittedNumberOfCallsInHalfOpenState(15) // Increased permitted calls
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(20)) // Increased wait duration
                .build();

        TimeLimiterConfig defaultTimeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(60))
                .build();

        return factory -> {
            factory.addCircuitBreakerCustomizer(circuitBreaker -> {
                log.info("Circuit breaker created/customized: {}", circuitBreaker.getName());
                setupEventPublishingForCircuitBreaker(circuitBreaker); // Your existing method
            });

            factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(defaultCircuitBreakerConfig)
                    .timeLimiterConfig(defaultTimeLimiterConfig)
                    .build());

            factory.configure(builder -> builder
                    .circuitBreakerConfig(defaultCircuitBreakerConfig)
                    .timeLimiterConfig(defaultTimeLimiterConfig)
                    .build(), ROUTE_WIDGET_SERVICE);
        };
    }

    /**
     * All CircuitBreakers need to have the Event Handlers registered, but only if not already done.
     *
     * @param circuitBreaker
     */
    private synchronized void setupEventPublishingForCircuitBreaker(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> {
                    CircuitBreaker.State fromState = event.getStateTransition().getFromState();
                    CircuitBreaker.State toState = event.getStateTransition().getToState();

                    log.info("Circuit breaker '{}' state changed from {} to {}", circuitBreaker.getName(), fromState, toState);

                    // Record metric for state transition
                    meterRegistry.counter("circuitbreaker.state.transition",
                            Arrays.asList(
                                    Tag.of("name", circuitBreaker.getName()),
                                    Tag.of("fromState", fromState.name()),
                                    Tag.of("toState", toState.name())
                            )).increment();

                    if (toState == CircuitBreaker.State.OPEN) {
                        log.warn("ALERT: Circuit breaker '{}' is now OPEN", circuitBreaker.getName());
                        meterRegistry.counter("circuitbreaker.opened",
                                List.of(Tag.of("name", circuitBreaker.getName()))).increment();
                    } else if (fromState == CircuitBreaker.State.OPEN && toState == CircuitBreaker.State.HALF_OPEN) {
                        log.info("Circuit breaker '{}' is now HALF-OPEN", circuitBreaker.getName());
                    } else if (fromState == CircuitBreaker.State.HALF_OPEN && toState == CircuitBreaker.State.CLOSED) {
                        log.info("Circuit breaker '{}' has RECOVERED and is now CLOSED", circuitBreaker.getName());
                        meterRegistry.counter("circuitbreaker.closed",
                                List.of(Tag.of("name", circuitBreaker.getName()))).increment();
                    }
                })
                .onError(event -> {
                    log.info("Circuit breaker '{}' error: {}", circuitBreaker.getName(), event.getThrowable().getMessage());
                    meterRegistry.counter("circuitbreaker.error",
                            Arrays.asList(
                                    Tag.of("name", circuitBreaker.getName()),
                                    Tag.of("exceptionClass", event.getThrowable().getClass().getSimpleName())
                            )).increment();
                })
                .onCallNotPermitted(event -> {
                    log.info("Circuit breaker '{}' rejected call", circuitBreaker.getName());
                    meterRegistry.counter("circuitbreaker.call.rejected",
                            List.of(Tag.of("name", circuitBreaker.getName()))).increment();
                });
    }
}