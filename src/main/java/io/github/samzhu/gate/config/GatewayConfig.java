package io.github.samzhu.gate.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import io.github.samzhu.gate.filter.ApiKeyInjectionFilter;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

/**
 * Spring Cloud Gateway Server MVC 路由配置
 */
@Configuration
public class GatewayConfig {

    private final AnthropicProperties anthropicProperties;
    private final ApiKeyInjectionFilter apiKeyInjectionFilter;

    public GatewayConfig(
            AnthropicProperties anthropicProperties,
            ApiKeyInjectionFilter apiKeyInjectionFilter) {
        this.anthropicProperties = anthropicProperties;
        this.apiKeyInjectionFilter = apiKeyInjectionFilter;
    }

    @Bean
    public RouterFunction<ServerResponse> gatewayRoutes() {
        URI anthropicUri = URI.create(anthropicProperties.baseUrl());

        return route("anthropic-messages")
            .POST("/v1/messages", http())
            .before(uri(anthropicUri))
            .before(apiKeyInjectionFilter)
            .build();
    }
}
