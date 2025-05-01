package io.softwarestrategies.projectx.gateway.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Component
@Slf4j
@RequiredArgsConstructor
public class LoggingForGatewayFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private final LoggingHandlerInterceptor loggingHandlerInterceptor;

    /**
     * Implementing the 'filter' method as required by the incorrect interface.
     * Note the signature R filter(ServerRequest request, HandlerFunction<T> next)
     * where T and R are constrained to ServerResponse in that incorrect interface.
     */
    @Override // This @Override assumes your compiler sees the 'filter' method in the interface
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        ServerResponse response = null;
        Exception exception = null;

        log.debug("Applying LoggingHandlerFilterFunction (adapted to incorrect interface) for request: {} {}", request.method(), request.uri());

        try {
            // --- Pre-processing using the LoggingHandlerInterceptor ---
            log.debug("Calling preHandle on LoggingHandlerInterceptor...");
            // Use the ServerRequest overload - this part of your interceptor is compatible
            // with the ServerRequest available here.
            if (!loggingHandlerInterceptor.preHandle(request, null, null)) {
                log.warn("LoggingHandlerInterceptor preHandle returned false for request: {} {}", request.method(), request.uri());
                return ServerResponse.status(HttpStatus.BAD_REQUEST).build();
            }
            log.debug("preHandle returned true. Proceeding with the next handler/filter.");

            // --- Proceed with the rest of the chain ---
            // The 'next' handler is of type HandlerFunction<ServerResponse> according to
            // the incorrect interface definition's T parameter.
            // Despite the confusing definition, 'next' in a filter chain context
            // is usually the subsequent filter or the final handler (your http() call).
            // We must call its 'handle' method, passing the request, to continue the chain.
            response = next.handle(request);
            log.debug("Next handler completed. Response status code: {}", response.statusCode().value());

        } catch (Exception e) {
            // --- Exception Handling ---
            exception = e;
            log.error("Exception occurred during request handling in adapted filter: " + e.getMessage(), e);
            throw e; // Re-throw the exception
        } finally {
            // --- Post-processing using the LoggingHandlerInterceptor ---
            log.debug("Calling afterCompletion on LoggingHandlerInterceptor...");
            // Use the ServerRequest/ServerResponse overload - this part of your interceptor is compatible
            try {
                loggingHandlerInterceptor.afterCompletion(request, response, null, exception);
                log.debug("afterCompletion completed successfully.");
            } catch (Exception cleanupException) {
                log.error("Exception during logging afterCompletion in adapted filter: " + cleanupException.getMessage(), cleanupException);
            }
        }

        log.debug("Finished LoggingHandlerFilterFunction (adapted) for request: {} {}", request.method(), request.uri());
        return response;
    }

}