package io.softwarestrategies.projectx.gateway.util;

import org.springframework.http.server.RequestPath;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Collections;
import java.util.Map;

public class PathVariableExtractor {

    /**
     * Checks if the request URI matches the given pattern and extracts path variables if it matches.
     *
     * @param requestPath the current ServerHttpRequest's RequestPath
     * @param pattern the path pattern to match
     * @return a Map containing the path variables if it matches, or an empty map if it does not
     */
    public static Map<String, String> matchAndExtract(RequestPath requestPath, String pattern) {
        PathPatternParser parser = new PathPatternParser();
        PathPattern pathPattern = parser.parse(pattern);
        PathPattern.PathMatchInfo pathMatchInfo = pathPattern.matchAndExtract(requestPath.pathWithinApplication());

        return pathMatchInfo != null ? pathMatchInfo.getUriVariables() : Collections.emptyMap();
    }

    /**
     * Checks if the request URI matches the given pattern.
     *
     * @param requestPath the current ServerHttpRequest's RequestPath
     * @param pattern the path pattern to match
     * @return true if the pattern matches, false otherwise
     */
    public static boolean isMatch(RequestPath requestPath, String pattern) {
        PathPatternParser parser = new PathPatternParser();
        PathPattern pathPattern = parser.parse(pattern);

        return pathPattern.matches(requestPath.pathWithinApplication());
    }
}

