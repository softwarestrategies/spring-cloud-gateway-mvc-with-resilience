package io.softwarestrategies.projectx.gateway.controller;

import io.softwarestrategies.projectx.gateway.config.ResilienceConfig;
import io.softwarestrategies.projectx.gateway.resilience.WidgetServiceFallback;
import io.softwarestrategies.projectx.gateway.web.OriginatingRequestForGatewayFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.RequestPath;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@RestController
@Slf4j
@RequiredArgsConstructor
public class GatewayFallbackController {

    private final WidgetServiceFallback widgetServiceFallback;

    @RequestMapping(
            path="/gateway-fallback/{routeName}",
            method = { RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH }
    )
    public ResponseEntity<Object> gatewayFallbackEndpoint(@PathVariable String routeName, HttpServletRequest httpServletRequest) {

        // Try to get the original request details from multiple sources, ThreadLocal first
        Map<String, Object> requestInfo = OriginatingRequestForGatewayFilter.RequestStorage.getRequestInfo();

        // If ThreadLocal failed, then try RequestContextHolder
        if (requestInfo == null) {
            try {
                RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
                if (attributes != null) {
                    requestInfo = (Map<String, Object>) attributes.getAttribute(
                            "originatingRequestInfo", RequestAttributes.SCOPE_REQUEST);
                }
            } catch (Exception e) {
                log.warn("Could not retrieve request info from RequestContextHolder: {}", e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();

        // Add request details to response if available
        if (requestInfo != null) {
            log.info("Found original request info with {} elements", requestInfo.size());
            response.put("originalRequest", requestInfo);
        } else {
            log.warn("Original request info not found, using current request");
            // Capture current request details
            Map<String, Object> currentRequestInfo = new HashMap<>();
            currentRequestInfo.put("path", httpServletRequest.getRequestURI());
            currentRequestInfo.put("method", httpServletRequest.getMethod());
            response.put("fallbackRequest", currentRequestInfo);
        }

        String requestMethod = requestInfo != null ? (String) requestInfo.get("method") : null;
        RequestPath requestPath = requestInfo != null ? (RequestPath) requestInfo.get("path") : null;
        String requestBody = requestInfo != null ? (String) requestInfo.get("body") : null;
        Map<String, String> requestHeaders = requestInfo != null ? (Map<String, String>) requestInfo.get("headers") : null;
        MultiValueMap<String, String> queryParams = requestInfo != null ? (MultiValueMap<String, String>) requestInfo.get("queryParams") : null;

        // Clear thread-local storage
        OriginatingRequestForGatewayFilter.RequestStorage.clear();

        ResponseEntity<Object> responseEntity = switch (routeName) {
            case ResilienceConfig.ROUTE_WIDGET_SERVICE -> widgetServiceFallback.callThruGateway(requestMethod, requestPath, requestBody, requestHeaders, queryParams);
            default -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Fallback for the following service route is not implemented: " + routeName);
        };

        return responseEntity;
    }
}