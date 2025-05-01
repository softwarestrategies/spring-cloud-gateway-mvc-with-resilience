package io.softwarestrategies.projectx.gateway.config;

import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.softwarestrategies.projectx.gateway.exception.RetryException;
import io.softwarestrategies.projectx.gateway.web.LoggingForGatewayFilter;
import io.softwarestrategies.projectx.gateway.web.OriginatingRequestForGatewayFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
@RequiredArgsConstructor
public class GatewayConfig {

    private final OriginatingRequestForGatewayFilter originatingRequestForGatewayFilter;
    private final LoggingForGatewayFilter loggingForGatewayFilter;
    private final RetryRegistry retryRegistry;

    private final String URI_CONFIG_EXTERNAL_SERVICE = "http://localhost:8070";

    @Bean
    public RouterFunction<ServerResponse> combinedGatewayRoutes() {
        return GatewayRouterFunctions.route()
                .add(route("external-service")
                        .filter(originatingRequestForGatewayFilter)
                        .filter(CircuitBreakerFilterFunctions.circuitBreaker(config -> {
                            config.setId(Resilience4jConfig.ROUTE_EXTERNAL_SERVICE);
                            config.setFallbackUri(URI.create("forward:/gateway-fallback/%s".formatted(Resilience4jConfig.ROUTE_EXTERNAL_SERVICE)));
                        }))
                        .route(RequestPredicates.path("/api/v1/external/**"),
                                requestRetryHandler(URI_CONFIG_EXTERNAL_SERVICE,"gatewayJitterRetry")
                        )
                        .filter(HandlerFilterFunction.ofRequestProcessor(rewritePath("/api/v1/external/(?<segment>.*)", "/api/v1/${segment}")))
                        .filter(loggingForGatewayFilter)
                        .build()
                )
                .add(route("local-service")
                        .route(RequestPredicates.path("/local-service/**"), http(URI_CONFIG_EXTERNAL_SERVICE))
                        .before(rewritePath("/local-service/(?<segment>.*)", "/api/v1/${segment}"))
                        .filter(loggingForGatewayFilter)
                        .build()
                )
                .build();
    }

    /**
     * HandlerFunction that implements retry with exponential backoff and jitter
     *
     * @param targetBaseUri
     * @param retryName
     * @return
     */
    private HandlerFunction<ServerResponse> requestRetryHandler(String targetBaseUri, String retryName) {

        URI targetURI = URI.create(targetBaseUri);
        RetryConfig retryConfig = retryRegistry.retry(retryName).getRetryConfig();

        return request -> {
            log.info("Processing request with custom resilience pattern to: {}", targetURI);

            int maxAttempts = retryConfig.getMaxAttempts();
            double jitterFactor = 0.2; // Assuming you want a fixed 20% jitter

            Exception lastException = null;
            ServerResponse finalResponse;

            // Get the request method for logging/debugging
            String requestMethod = request.method().name();
            log.info("Request method: {}", requestMethod);

            // Get the body once before the retry loop to avoid multiple reads
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

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long attemptStart = System.currentTimeMillis();
                try {
                    log.info("Attempt #{} of {} to call service: {} (method: {})",
                            attempt, maxAttempts, targetURI, requestMethod);

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
                                // Copy all headers more efficiently
                                incomingHeaders.forEach(downstreamHeaders::addAll);
                            });

                    // Include Request Body if present and method supports it - optimized for POST
                    if (requestBodyBytes.length > 0
                            && request.method() != HttpMethod.GET
                            && request.method() != HttpMethod.HEAD
                            && request.method() != HttpMethod.DELETE
                            && request.method() != HttpMethod.OPTIONS) {

                        // Set body with pre-cached content type
                        requestSpec.contentType(contentType).body(requestBodyBytes);
                        log.debug("Added body with content type: {}", contentType);
                    }
                    long requestEnd = System.currentTimeMillis();
                    log.debug("Request setup took {}ms", requestEnd - requestStart);

                    // Execute the request with timing
                    long executeStart = System.currentTimeMillis();
                    ResponseEntity<byte[]> responseEntityWithBody = requestSpec.retrieve().toEntity(byte[].class);
                    long executeEnd = System.currentTimeMillis();
                    log.debug("Request execution took {}ms", executeEnd - executeStart);

                    // Build ServerResponse from the ResponseEntity
                    finalResponse = ServerResponse.status(responseEntityWithBody.getStatusCode())
                            .headers(headers -> headers.addAll(responseEntityWithBody.getHeaders()))
                            .body(responseEntityWithBody.getBody());

                    // Check if we got a status code that should trigger retry based on custom logic
                    int statusCode = responseEntityWithBody.getStatusCode().value();
                    boolean isRetryableStatus = isRetryableStatusCode(statusCode);

                    if (isRetryableStatus) {
                        log.info("Received status {} - will retry based on custom logic", statusCode);

                        if (attempt < maxAttempts) {
                            long delay = retryConfig.getIntervalFunction().apply(attempt);
                            delay = addJitter(delay, jitterFactor);

                            log.info("Waiting {}ms before retry #{}", delay, attempt + 1);
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
                            log.info("Max attempts reached after receiving status {}", statusCode);
                            throw new RetryException("Max retry attempts exceeded with status: " + statusCode);
                        }
                    }

                    long attemptEnd = System.currentTimeMillis();
                    log.info("Request succeeded on attempt #{} in {}ms", attempt, attemptEnd - attemptStart);
                    return finalResponse;

                } catch (Exception e) {
                    lastException = e;
                    long attemptEnd = System.currentTimeMillis();
                    log.warn("Attempt #{} failed after {}ms with exception: {}",
                            attempt, attemptEnd - attemptStart, e.getMessage());

                    // Check if an exception is retryable based on custom logic
                    boolean isRetryableException = isRetryableException(e);

                    if (isRetryableException) {
                        if (attempt < maxAttempts) {
                            long delay = retryConfig.getIntervalFunction().apply(attempt);
                            delay = addJitter(delay, jitterFactor);
                            log.info("Waiting {}ms before retry #{}", delay, attempt + 1);
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

    private long addJitter(long delay, double jitterFactor) {
        double jitter = delay * jitterFactor * ThreadLocalRandom.current().nextDouble();
        return delay + Math.round(jitter);
    }

    // Helper method to check if a status code is retryable
    private boolean isRetryableStatusCode(int statusCode) {
        // Define the status codes you want to retry based on your needs
        return statusCode == HttpStatus.TOO_MANY_REQUESTS.value() ||
                statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value() ||
                statusCode == HttpStatus.BAD_GATEWAY.value() ||
                statusCode == HttpStatus.SERVICE_UNAVAILABLE.value() ||
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
     * this approach was the best.
     *
     * @param request
     * @return
     */
    private byte[] getCachedRequestBodyBytes(ServerRequest request) {
        log.debug("Attempting to get cached or read request body for path: {}", request.path());

        // Check if body is already cached using the SAME key as the Originating Request for Gateway filter
        Optional<Object> cachedBody = request.attributes().entrySet().stream()
                .filter(entry -> OriginatingRequestForGatewayFilter.ORIGINATING_REQUEST_BODY.equals(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();

        if (cachedBody.isPresent() && cachedBody.get() instanceof byte[]) {
            byte[] body = (byte[]) cachedBody.get();
            log.debug("Using cached request body from attributes with key {}. Size: {}", OriginatingRequestForGatewayFilter.ORIGINATING_REQUEST_BODY, body.length);
            return body;
        }

        // If not cached, attempt to read from the input stream
        log.debug("Request body not found in attributes with key {}, attempting to read from InputStream.", OriginatingRequestForGatewayFilter.ORIGINATING_REQUEST_BODY);
        byte[] bodyBytes = new byte[0];
        try {
            // Access the underlying HttpServletRequest
            HttpServletRequest servletRequest = request.servletRequest();
            log.debug("Accessed underlying HttpServletRequest.");

            InputStream inputStream = servletRequest.getInputStream();

            if (inputStream != null) {
                log.debug("InputStream obtained.");
                try {
                    int available = inputStream.available();
                    log.debug("InputStream.available() returned: {}", available);
                } catch (IOException e) {
                    log.warn("Error calling inputStream.available(): {}", e.getMessage());
                }

                log.debug("Attempting to copy InputStream to byte array.");
                bodyBytes = FileCopyUtils.copyToByteArray(inputStream);
                log.debug("Successfully read {} bytes from InputStream.", bodyBytes.length);

                // 3. Cache the read bytes using the SAME key as the filter
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