package io.softwarestrategies.projectx.gateway.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GatewayMvcInterceptorConfig implements WebMvcConfigurer {

    @Autowired
    private LoggingHandlerInterceptor loggingHandlerInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Add the interceptors to the registry
        // You can specify paths to apply these interceptors to
        registry.addInterceptor(loggingHandlerInterceptor).addPathPatterns("/**");
    }
}