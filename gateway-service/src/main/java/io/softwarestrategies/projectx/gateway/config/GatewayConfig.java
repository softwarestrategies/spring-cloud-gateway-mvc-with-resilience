package io.softwarestrategies.projectx.gateway.config;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryRegistry;
import io.softwarestrategies.projectx.gateway.exception.RetryException;
import io.softwarestrategies.projectx.gateway.web.LoggingForGatewayFilter;
import io.softwarestrategies.projectx.gateway.web.OriginatingRequestForGatewayFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.function.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.rewritePath;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@Configuration
@Slf4j
public class GatewayConfig {

    private final OriginatingRequestForGatewayFilter originatingRequestForGatewayFilter;
    private final LoggingForGatewayFilter loggingForGatewayFilter;
    private final IntervalFunction gatewayRouteRandomBackoffIntervalFunction;

    private static final String URI_LOCAL_SERVICE = "http://localhost:8080";
    private static final String URI_WIDGET_SERVICE = "http://localhost:8070";

    public static final int GATEWAY_RETRY_MAX_ATTEMPTS = 5;
    public static final long GATEWAY_RETRY_INITIAL_INTERVAL = 1000;
    public static final double GATEWAY_RETRY_MULTIPLIER = 1.5;
    public static final double GATEWAY_RETRY_RANDOMIZATION_FACTOR = 0.2;  // 20% jitter

    public GatewayConfig(OriginatingRequestForGatewayFilter originatingRequestForGatewayFilter,
                         LoggingForGatewayFilter loggingForGatewayFilter, RetryRegistry retryRegistry) {
        this.originatingRequestForGatewayFilter = originatingRequestForGatewayFilter;
        this.loggingForGatewayFilter = loggingForGatewayFilter;

        this.gatewayRouteRandomBackoffIntervalFunction = IntervalFunction.ofExponentialRandomBackoff(
                GATEWAY_RETRY_INITIAL_INTERVAL,
                GATEWAY_RETRY_MULTIPLIER,
                GATEWAY_RETRY_RANDOMIZATION_FACTOR
        );
    }

    @Bean
    public RouterFunction<ServerResponse> combinedGatewayRoutes() {
        return GatewayRouterFunctions.route()
                .add(route("widget-service")
                        .filter(originatingRequestForGatewayFilter)
                        .filter(createCircuitBreakerFilter(ResilienceConfig.ROUTE_WIDGET_SERVICE))
                        .route(RequestPredicates.path("/api/v1/widget/**"), retryHandler(URI_WIDGET_SERVICE))
                        .filter(HandlerFilterFunction.ofRequestProcessor(rewritePath("/api/v1/widget/(?<segment>.*)", "/api/v1/${segment}")))
                        .filter(loggingForGatewayFilter)
                        .build()
                )
                .add(route("local-service")
                        .route(RequestPredicates.path("/local-service/**"), http(URI_LOCAL_SERVICE))
                        .before(rewritePath("/local-service/(?<segment>.*)", "/api/v1/${segment}"))
                        .filter(loggingForGatewayFilter)
                        .build()
                )
                .build();
    }

    /**
     * HandlerFilterFunction that creates/handles the circuit breaker for a given route
     *
     * @param routeId
     * @return
     */
    private HandlerFilterFunction<ServerResponse, ServerResponse> createCircuitBreakerFilter(String routeId) {
        return CircuitBreakerFilterFunctions.circuitBreaker(config -> {
            config.setId(routeId);
            config.setFallbackUri(URI.create("forward:/gateway-fallback/%s".formatted(routeId)));
        });
    }

    /**
     * HandlerFunction that implements retry with exponential backoff and jitter
     *
     * @param targetBaseUri
     * @return
     */
    private HandlerFunction<ServerResponse> retryHandler(String targetBaseUri) {

        URI targetURI = URI.create(targetBaseUri);

        return request -> {

            log.debug("Processing gateway request with resilience: {} {}", request.method().name(), targetURI);

            Exception lastException = null;
            ServerResponse finalResponse;

            // Get the body once before the retry loop to avoid multiple reads.
            long bodyStart = System.currentTimeMillis();
            byte[] requestBodyBytes = getCachedRequestBodyBytes(request);
            long bodyEnd = System.currentTimeMillis();

            log.debug("Body reading took {}ms, body size: {}", bodyEnd - bodyStart, requestBodyBytes.length);

            // Cache the content type for POST requests to avoid repeated header access
            HttpHeaders incomingHeaders = request.headers().asHttpHeaders();
            MediaType contentType = incomingHeaders.getContentType();
            if (contentType == null) {
                contentType = MediaType.APPLICATION_JSON; // Default content type
            }

            int maxAttempts = GATEWAY_RETRY_MAX_ATTEMPTS;
            double jitterFactor = ResilienceConfig.RANDOMIZATION_FACTOR;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long attemptStart = System.currentTimeMillis();
                try {
                    log.debug("Attempt #{} of {} to call service: {} (method: {})",
                            attempt, maxAttempts, targetURI, request.method().name());

                    // Build downstream URI including query parameters
                    long uriStart = System.currentTimeMillis();
                    UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUri(targetURI)
                            .path(request.uri().getPath());

                    // Add query parameters from the original request, handling null
                    String query = request.uri().getQuery();
                    if (query != null) {
                        uriBuilder.query(query);
                    }

                    URI downstreamUri = uriBuilder.build().toUri();
                    long uriEnd = System.currentTimeMillis();
                    log.debug("URI building took {}ms", uriEnd - uriStart);

                    // Create and configure RestClient request
                    long requestStart = System.currentTimeMillis();
                    RestClient.RequestBodyUriSpec requestSpec = (RestClient.RequestBodyUriSpec) RestClient.create()
                            .method(request.method())
                            .uri(downstreamUri)
                            .headers(downstreamHeaders -> {
                                incomingHeaders.forEach(downstreamHeaders::addAll);
                            });

                    // Include Request Body if present and method supports it - optimized for POST
                    if (requestBodyBytes.length > 0
                            && request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD
                            && request.method() != HttpMethod.DELETE && request.method() != HttpMethod.OPTIONS) {

                        // Set body with pre-cached content type
                        requestSpec.contentType(contentType).body(requestBodyBytes);
                        log.debug("Added body with content type: {}", contentType);
                    }

                    // Execute the request with timing
                    long executeStart = System.currentTimeMillis();
                    ResponseEntity<byte[]> responseEntityWithBody = requestSpec.retrieve().toEntity(byte[].class);
                    long executeEnd = System.currentTimeMillis();

                    log.debug("Request execution took {}ms", executeEnd - executeStart);

                    finalResponse = ServerResponse
                            .status(responseEntityWithBody.getStatusCode())
                            .headers(headers -> headers.addAll(responseEntityWithBody.getHeaders()))
                            .body(responseEntityWithBody.getBody());

                    int statusCode = responseEntityWithBody.getStatusCode().value();

                    boolean isRetryableStatus = isRetryableStatusCode(statusCode);

                    if (isRetryableStatus) {
                        log.debug("Received status {} - will retry based on custom logic", statusCode);

                        if (attempt < maxAttempts) {
                            long delay = gatewayRouteRandomBackoffIntervalFunction.apply(attempt);
                            delay = addJitter(delay, jitterFactor);
                            log.debug("Waiting {}ms before retry #{}", delay, attempt + 1);

                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException ie) {
                                // Instead of throwing, just log and continue
                                log.warn("Sleep interrupted during retry for status code {}, continuing with next attempt", statusCode);

                                // Preserve the interrupted status
                                Thread.currentThread().interrupt();

                                // Continue with the next retry attempt
                                continue;
                            }
                            continue;

                        } else {
                            log.debug("Max attempts reached after receiving status {}", statusCode);
                            throw new RetryException("Max retry attempts exceeded with status: " + statusCode);
                        }
                    }

                    long attemptEnd = System.currentTimeMillis();
                    log.debug("Request succeeded on attempt #{} in {}ms", attempt, attemptEnd - attemptStart);

                    return finalResponse;

                } catch (Exception e) {

                    lastException = e;
                    long attemptEnd = System.currentTimeMillis();

                    log.warn("Attempt #{} failed after {}ms with exception: {}",
                            attempt, attemptEnd - attemptStart, e.getMessage());

                    boolean isRetryableException = isRetryableException(e);

                    if (isRetryableException) {
                        if (attempt < maxAttempts) {
                            long delay = gatewayRouteRandomBackoffIntervalFunction.apply(attempt);
                            delay = addJitter(delay, jitterFactor);
                            log.debug("Waiting {}ms before retry #{}", delay, attempt + 1);

                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException ie) {
                                // Instead of throwing an exception, log a warning and continue
                                log.warn("Sleep interrupted during retry, continuing with next attempt");
                                // Preserve the interrupted status
                                Thread.currentThread().interrupt();
                                // Continue with the next retry attempt
                                continue;
                            }
                            continue;
                        } else {
                            log.error("All {} retry attempts failed with exception", maxAttempts);
                            throw e; // Re-throw to trigger fallback
                        }
                    } else {
                        log.error("Attempt #{} failed with non-retryable exception", attempt);
                        throw e; // Re-throw non-retryable exceptions immediately to trigger fallback
                    }
                }
            }

            throw lastException != null ? lastException :
                    new RuntimeException("Custom retry logic failed unexpectedly");
        };
    }

    /**
     *
     *
     * @param delay
     * @param jitterFactor
     * @return
     */
    private long addJitter(long delay, double jitterFactor) {
        double jitter = delay * jitterFactor * ThreadLocalRandom.current().nextDouble();
        return delay + Math.round(jitter);
    }

    /**
     * Check if Status Code is retryable.
     *
     * @param statusCode
     * @return
     */
    private boolean isRetryableStatusCode(int statusCode) {
        return statusCode == HttpStatus.TOO_MANY_REQUESTS.value() || statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value() ||
                statusCode == HttpStatus.BAD_GATEWAY.value() || statusCode == HttpStatus.SERVICE_UNAVAILABLE.value() ||
                statusCode == HttpStatus.GATEWAY_TIMEOUT.value();
    }

    /**
     * Helper method to check if an exception is retryable based on custom logic.
     *
     * @param e
     * @return
     */
    private boolean isRetryableException(Exception e) {
        return e instanceof ConnectException || e instanceof RestClientException || e instanceof TimeoutException ||
                e instanceof RetryException;
    }

    /**
     * Body reading method to ensure caching.  Weird things can happen to the request body once the retries begin and
     * this approach was the best.  Ideally, it is already available from the call to cache the Originating Request
     *
     * @param request
     * @return
     */
    private byte[] getCachedRequestBodyBytes(ServerRequest request) {

        // Check if body is already cached using the SAME key as the Originating Request for Gateway filter.  If so, return it
        Optional<Object> cachedBody = request.attributes().entrySet().stream()
                .filter(entry -> OriginatingRequestForGatewayFilter.ORIGINATING_REQUEST_BODY.equals(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();

        if (cachedBody.isPresent() && cachedBody.get() instanceof byte[]) {
            byte[] body = (byte[]) cachedBody.get();
            return body;
        }

        // If not cached, attempt to read from the input stream
        byte[] bodyBytes = new byte[0];

        try {
            // Access the underlying HttpServletRequest
            HttpServletRequest servletRequest = request.servletRequest();

            InputStream inputStream = servletRequest.getInputStream();

            if (inputStream != null) {
                try {
                    int available = inputStream.available();
                    log.debug("InputStream.available() returned: {}", available);
                } catch (IOException e) {
                    log.warn("Error calling inputStream.available(): {}", e.getMessage());
                }

                bodyBytes = FileCopyUtils.copyToByteArray(inputStream);

                // Cache the read bytes using the SAME key as the filter
                request.attributes().put(OriginatingRequestForGatewayFilter.ORIGINATING_REQUEST_BODY, bodyBytes);
                log.debug("Cached request body in attributes with key {}. Size: {}", OriginatingRequestForGatewayFilter.ORIGINATING_REQUEST_BODY, bodyBytes.length);

            } else {
                log.debug("InputStream obtained is null.");
            }

        } catch (IOException e) {
            log.error("Error reading request body from InputStream: {}", e.getMessage(), e);
            return new byte[0];

        } catch (Exception e) {
            log.error("Unexpected error during request body reading process: {}", e.getMessage(), e);
            return new byte[0];
        }

        return bodyBytes;
    }
}