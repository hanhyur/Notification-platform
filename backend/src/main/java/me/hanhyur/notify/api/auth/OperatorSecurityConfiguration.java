package me.hanhyur.notify.api.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorAccounts.class)
class OperatorSecurityConfiguration {

    @Bean
    AuthenticationManager operatorAuthentication(OperatorAccounts accounts) {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        return new ProviderManager(provider);
    }

    @Bean
    HttpSessionSecurityContextRepository operatorContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository operatorCsrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SessionAuthenticationStrategy operatorSessionRotation(HttpSessionCsrfTokenRepository tokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(tokens)));
    }

    @Bean
    SecurityFilterChain operatorApi(HttpSecurity http, HttpSessionSecurityContextRepository contexts,
                                    HttpSessionCsrfTokenRepository tokens, OperatorAccounts accounts,
                                    JsonMapper json, Clock clock) {
        var problems = new OperatorProblemWriter(json);
        return http.securityMatcher("/api/v1/**")
                .securityContext(context -> context.securityContextRepository(contexts))
                .csrf(csrf -> csrf.csrfTokenRepository(tokens).csrfTokenRequestHandler(new HeaderCsrfTokens()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .anyRequest().hasRole("OPERATOR"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                problems.write(request, response, OperatorProblemWriter.Reason.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, ex) -> {
                            boolean login = "/api/v1/auth/login".equals(request.getServletPath());
                            var current = SecurityContextHolder.getContext().getAuthentication();
                            boolean signedIn = current != null && current.getPrincipal() instanceof OperatorSession;
                            problems.write(request, response, login || signedIn
                                    ? OperatorProblemWriter.Reason.FORBIDDEN : OperatorProblemWriter.Reason.UNAUTHENTICATED);
                        }))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.cacheControl(cache -> cache.disable())
                        .addHeaderWriter(new StaticHeadersWriter(HttpHeaders.CACHE_CONTROL, "no-store")))
                .addFilterAfter(new OperatorRequestLimit(accounts, problems, clock), AuthorizationFilter.class)
                .build();
    }
}
