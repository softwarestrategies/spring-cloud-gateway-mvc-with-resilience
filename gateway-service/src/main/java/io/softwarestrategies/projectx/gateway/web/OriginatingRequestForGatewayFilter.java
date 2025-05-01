package io.softwarestrategies.projectx.gateway.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class OriginatingRequestForGatewayFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    public static final String ORIGINATING_REQUEST_INFO = "originatingRequestInfo";
    public static final String ORIGINATING_REQUEST_BODY = "originatingRequestBody";

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {

        // Store complete request information in a serializable map
        Map<String, Object> requestInfo = captureRequestDetails(request);

        // Store body separately to avoid attempting to serialize the request
        byte[] requestBodyBytes = null;
        try {
            // This is a more reliable way to capture the body for potential reuse
            requestBodyBytes = getRequestBodyBytes(request);
            if (requestBodyBytes != null && requestBodyBytes.length > 0) {
                // Store body as byte array for potential reuse
                request.attributes().put(ORIGINATING_REQUEST_BODY, requestBodyBytes);

                // Also store as string in requestInfo if it's text-based
                try {
                    String bodyAsString = new String(requestBodyBytes, StandardCharsets.UTF_8);
                    requestInfo.put("body", bodyAsString);
                } catch (Exception e) {
                    log.debug("Could not convert body to string: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to capture request body: {}", e.getMessage());
        }

        // Store in request attributes
        request.attributes().put(ORIGINATING_REQUEST_INFO, requestInfo);

        // Also store in the servlet request attributes for more reliable access in fallback
        request.servletRequest().setAttribute(ORIGINATING_REQUEST_INFO, requestInfo);
        if (requestBodyBytes != null) {
            request.servletRequest().setAttribute(ORIGINATING_REQUEST_BODY, requestBodyBytes);
        }

        // Use RequestContextHolder too
        try {
            RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
            attributes.setAttribute(ORIGINATING_REQUEST_INFO, requestInfo, RequestAttributes.SCOPE_REQUEST);
            if (requestBodyBytes != null) {
                attributes.setAttribute(ORIGINATING_REQUEST_BODY, requestBodyBytes, RequestAttributes.SCOPE_REQUEST);
            }
            log.info("Request info stored in RequestContextHolder for path: {}", request.path());
        } catch (Exception e) {
            log.error("Failed to store request in RequestContextHolder: {}", e.getMessage());
        }

        // Thread-local storage as backup
        RequestStorage.setRequestInfo(requestInfo);
        if (requestBodyBytes != null) {
            RequestStorage.setRequestBody(requestBodyBytes);
        }

        return next.handle(request);
    }

    private Map<String, Object> captureRequestDetails(ServerRequest request) {
        Map<String, Object> requestInfo = new HashMap<>();

        // Capture basic request info
        requestInfo.put("method", request.method().name());
        requestInfo.put("path", request.requestPath());
        requestInfo.put("uri", request.uri().toString());

        // Capture headers
        Map<String, List<String>> headers = new HashMap<>();
        request.headers().asHttpHeaders().forEach(headers::put);
        requestInfo.put("headers", headers);

        // Capture query parameters
        requestInfo.put("queryParams", request.params());

        // Path variables if any
        Map<String, String> pathVariables = request.pathVariables();
        requestInfo.put("pathVariables", new HashMap<>(request.pathVariables()));

        return requestInfo;
    }

    private byte[] getRequestBodyBytes(ServerRequest request) {
        try {
            // This requires the underlying Servlet request
            InputStream inputStream = request.servletRequest().getInputStream();
            // Check if there is a body before attempting to copy
            if (inputStream != null && inputStream.available() > 0) {
                return FileCopyUtils.copyToByteArray(inputStream);
            }
            return new byte[0]; // Return empty byte array if no body
        } catch (IOException e) {
            log.error("Error reading request body", e);
            return new byte[0]; // Return empty on error
        }
    }

    // Static utility class for thread-local storage
    public static class RequestStorage {
        private static final ThreadLocal<Map<String, Object>> REQUEST_INFO = new ThreadLocal<>();
        private static final ThreadLocal<byte[]> REQUEST_BODY = new ThreadLocal<>();

        public static void setRequestInfo(Map<String, Object> requestInfo) {
            REQUEST_INFO.set(requestInfo);
        }

        public static Map<String, Object> getRequestInfo() {
            return REQUEST_INFO.get();
        }

        public static void setRequestBody(byte[] body) {
            REQUEST_BODY.set(body);
        }

        public static byte[] getRequestBody() {
            return REQUEST_BODY.get();
        }

        public static void clear() {
            REQUEST_INFO.remove();
            REQUEST_BODY.remove();
        }
    }
}