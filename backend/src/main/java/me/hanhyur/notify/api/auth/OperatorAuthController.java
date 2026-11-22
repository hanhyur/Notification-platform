package me.hanhyur.notify.api.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
class OperatorAuthController {

    private final AuthenticationManager authentication;
    private final OperatorAccounts accounts;
    private final HttpSessionSecurityContextRepository contexts;
    private final SessionAuthenticationStrategy rotation;
    private final boolean secureCookie;

    OperatorAuthController(AuthenticationManager authentication, OperatorAccounts accounts,
                           HttpSessionSecurityContextRepository contexts, SessionAuthenticationStrategy rotation,
                           @Value("${server.servlet.session.cookie.secure}") boolean secureCookie) {
        this.authentication = authentication;
        this.accounts = accounts;
        this.contexts = contexts;
        this.rotation = rotation;
        this.secureCookie = secureCookie;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    OperatorSession login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        var credentials = UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password());
        var verified = authentication.authenticate(credentials);
        var operator = accounts.session(verified.getName());
        var signedIn = UsernamePasswordAuthenticationToken.authenticated(operator, null, verified.getAuthorities());
        establishSession(signedIn, request, response);
        return operator;
    }

    private void establishSession(Authentication signedIn, HttpServletRequest request, HttpServletResponse response) {
        rotation.onAuthentication(signedIn, request, response);

        SecurityContext emptyContext = SecurityContextHolder.createEmptyContext();
        emptyContext.setAuthentication(signedIn);
        SecurityContextHolder.setContext(emptyContext);

        contexts.saveContext(emptyContext, request, response);
    }

    @GetMapping("/session")
    OperatorSession session(@AuthenticationPrincipal OperatorSession operator) {
        return operator;
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(Authentication current, HttpServletRequest request, HttpServletResponse response) {
        var logout = new SecurityContextLogoutHandler();
        logout.setSecurityContextRepository(contexts);
        logout.logout(request, response, current);
        var expired = ResponseCookie.from("SESSION", "").path("/").httpOnly(true)
                .secure(secureCookie).sameSite("Lax").maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail unauthenticated(HttpServletRequest request) {
        return OperatorProblemWriter.Reason.UNAUTHENTICATED.body(request);
    }

    record CsrfResponse(String token, String headerName) {
    }

    record LoginRequest(@NotEmpty @CodePointLength(max = 100) String username,
                        @NotEmpty @CodePointLength(max = 200) String password) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static LoginRequest fromJson(JsonNode node) {
            if (!node.isObject() || !Set.of("username", "password").containsAll(node.propertyNames())) {
                throw new IllegalArgumentException("로그인 입력 형식이 올바르지 않습니다.");
            }
            return new LoginRequest(value(node, "username"), value(node, "password"));
        }

        private static String value(JsonNode node, String field) {
            if (!node.hasNonNull(field)) {
                return null;
            }
            if (!node.get(field).isString()) {
                throw new IllegalArgumentException("로그인 입력은 문자열이어야 합니다.");
            }
            return node.get(field).asString();
        }

        @Override
        public String toString() {
            return "LoginRequest";
        }
    }
}
