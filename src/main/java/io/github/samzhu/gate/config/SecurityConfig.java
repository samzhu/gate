package io.github.samzhu.gate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置
 * 配置 OAuth2 Resource Server 使用 JWT 驗證 (僅 JWKS)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
