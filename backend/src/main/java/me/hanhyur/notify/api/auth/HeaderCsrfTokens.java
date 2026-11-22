package me.hanhyur.notify.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.util.function.Supplier;

final class HeaderCsrfTokens implements CsrfTokenRequestHandler {

    private final XorCsrfTokenRequestAttributeHandler masked = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
        masked.handle(request, response, token);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        var headers = request.getHeaders(token.getHeaderName());
        if (!headers.hasMoreElements()) {
            return null;
        }
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value.isBlank() || value.contains(",")) {
            return null;
        }
        return masked.resolveCsrfTokenValue(request, token);
    }
}
