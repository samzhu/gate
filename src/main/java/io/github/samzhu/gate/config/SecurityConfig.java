package io.github.samzhu.gate.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 安全配置
 *
 * <p>配置 OAuth2 Resource Server 使用 JWT 驗證，實現 API 閘道的認證機制：
 * <ul>
 *   <li>JWT 驗證透過 JWKS（JSON Web Key Set）端點取得公鑰</li>
 *   <li>無狀態 Session（適合 API Gateway）</li>
 *   <li>停用 CSRF（RESTful API 不需要）</li>
 * </ul>
 *
 * <p>端點權限：
 * <ul>
 *   <li>{@code /actuator/**} - 公開存取（健康檢查、指標）</li>
 *   <li>{@code /api/event_logging/batch} - 公開存取（Anthropic 1P 遙測 stub）</li>
 *   <li>其他端點 - 需要有效 JWT Token</li>
 * </ul>
 *
 * <p>JWKS 配置位於 {@code application.yaml}：
 * <pre>{@code
 * spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://your-auth-server/.well-known/jwks.json
 * }</pre>
 *
 * @see io.github.samzhu.gate.exception.GlobalExceptionHandler
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("Configuring OAuth2 Resource Server with JWT authentication");
        http
            // 停用 CSRF (API Gateway 不需要)
            .csrf(csrf -> csrf.disable())
            // 無狀態 Session
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 端點權限配置
            .authorizeHttpRequests(auth -> auth
                // Actuator 端點公開
                .requestMatchers("/actuator/**").permitAll()
                // Anthropic 1P 遙測 stub 公開（Claude Code 自動發送）
                .requestMatchers("/api/event_logging/batch").permitAll()
                // 其他所有請求需要認證
                .anyRequest().authenticated()
            )
            // OAuth2 Resource Server - JWT 驗證
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {
                    // JWT 配置由 application.yaml 中的 jwk-set-uri 提供
                    // Spring Security 會自動從 JWKS 端點取得公鑰驗證 JWT
                })
            );

        return http.build();
    }
}
