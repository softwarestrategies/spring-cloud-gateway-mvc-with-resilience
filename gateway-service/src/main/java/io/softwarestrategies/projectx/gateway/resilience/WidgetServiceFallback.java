package io.softwarestrategies.projectx.gateway.resilience;

import io.softwarestrategies.projectx.gateway.util.PathVariableExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

@Service
@Slf4j
public class WidgetServiceFallback {

    private final Map<String, BiFunction<RequestPath, MultiValueMap<String, String>, ResponseEntity<Object>>> gatewayFallbackHandlers;

    private static final String GATEWAY_PATHPATTERN_GET_WIDGET = "/api/v1/external/widgets/{id}";

    public WidgetServiceFallback() {
        gatewayFallbackHandlers = new HashMap<>();
        gatewayFallbackHandlers.put(GATEWAY_PATHPATTERN_GET_WIDGET, (requestPath, requestParams) -> fallbackForGetWidget(requestParams));
    }

    /**
     *
     *
     * @param requestMethod
     * @param requestPath
     * @param requestBody
     * @param requestHeaders
     * @param requestParams
     * @return
     */
    public ResponseEntity<Object> callThruGateway(String requestMethod, RequestPath requestPath,
                                                  String requestBody, Map<String, String> requestHeaders,
                                                  MultiValueMap<String, String> requestParams) {

        for (Map.Entry<String, BiFunction<RequestPath, MultiValueMap<String, String>, ResponseEntity<Object>>> entry :
                gatewayFallbackHandlers.entrySet()) {
            if (PathVariableExtractor.isMatch(requestPath, entry.getKey())) {
                return entry.getValue().apply(requestPath, requestParams);
            }
        }

        return new ResponseEntity<>("No Gateway Fallback for: /external" + requestPath.pathWithinApplication(), HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     *
     * @param requestParams
     * @return
     */
    private ResponseEntity<Object> fallbackForGetWidget(MultiValueMap<String, String> requestParams) {
        String version = requestParams.getFirst("version");
        return ResponseEntity.ok().body("Fallback for getWidget:  version=%s".formatted(version != null ? version : "null"));
    }
}

