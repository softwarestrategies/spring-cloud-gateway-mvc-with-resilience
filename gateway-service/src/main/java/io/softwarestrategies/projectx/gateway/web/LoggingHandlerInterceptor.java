package io.softwarestrategies.projectx.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class LoggingHandlerInterceptor implements HandlerInterceptor {

    /**
     * Log request details before processing
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Standard HttpServletRequest logging
        log.info("Request URI: {}", request.getRequestURI());
        log.info("Request Method: {}", request.getMethod());
        log.info("Request Headers: {}", getHeadersAsMap(request));
        return true;
    }

    /**
     * Log request details before processing - ServerRequest version for functional routing
     */
    public boolean preHandle(ServerRequest request, ServerResponse response, Object handler) throws Exception {
        // WebMvc.fn ServerRequest logging
        log.info("Request URI: {}", request.uri());
        log.info("Request Method: {}", request.method());
        log.info("Request Headers: {}", request.headers().asHttpHeaders());
        return true;
    }

    /**
     * Log response details after completion - ServerRequest version for functional routing
     */
    public void afterCompletion(ServerRequest request, ServerResponse response, Object handler, Exception ex) throws Exception {
        // The Retry/CircuitBreaker logic for Gateway can have this as null
        if (response != null) {
            log.info("Response Status: {}", response.statusCode());
        }

        if (ex != null) {
            log.error("Exception during request processing: ", ex);
        }
    }

    /**
     * Log response details after completion
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // Standard HttpServletResponse logging
        log.info("Response Status: {}", response.getStatus());
        log.info("Response Headers: {}", getResponseHeadersAsMap(response));

        if (ex != null) {
            log.error("Exception during request processing: ", ex);
        }
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        // This method is not used in this implementation
    }

    /**
     * Convert HttpServletRequest headers to a map for logging
     */
    private Map<String, String> getHeadersAsMap(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();

        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }

        return headers;
    }

    /**
     * Convert HttpServletResponse headers to a map for logging
     */
    private Map<String, String> getResponseHeadersAsMap(HttpServletResponse response) {
        Map<String, String> headers = new HashMap<>();
        Collection<String> headerNames = response.getHeaderNames();

        for (String headerName : headerNames) {
            headers.put(headerName, response.getHeader(headerName));
        }

        return headers;
    }
}