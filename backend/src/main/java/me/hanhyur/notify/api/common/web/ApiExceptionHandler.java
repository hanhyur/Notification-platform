package me.hanhyur.notify.api.common.web;

import org.jspecify.annotations.Nullable;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.*;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private final RequestIdFilter requestIdFilter;

    public ApiExceptionHandler(RequestIdFilter requestIdFilter) {
        this.requestIdFilter = requestIdFilter;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        HttpStatusCode validationStatus = HttpStatus.UNPROCESSABLE_CONTENT;

        Map<String, List<String>> fieldErrors = new LinkedHashMap<>();

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.computeIfAbsent(
                    error.getField(), key -> new ArrayList<>()
            ).add(Objects.requireNonNullElse(
                    error.getDefaultMessage(),
                    "입력값이 올바르지 않습니다."
            ));
        }

        ProblemDetail body = problem(
                validationStatus, "UnprocessableEntity",
                "필드, 예약 범위 또는 기간 조건을 만족하지 않는다.",
                "VALIDATION_FAILED", fieldErrors, request
        );

        return handleExceptionInternal(ex, body, headers, validationStatus, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ProblemDetail body = problem(
                status, "BadRequest",
                "JSON 형식, query 또는 cursor 형식이 올바르지 않다.",
                "INVALID_REQUEST", Map.of(), request
        );

        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ProblemDetail body = problem(
                status, "UnsupportedMediaType",
                "application/json 요청이 필요하다.",
                "UNSUPPORTED_MEDIA_TYPE", Map.of(), request
        );

        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private ProblemDetail problem(
            HttpStatusCode status,
            String title,
            String detail,
            String code,
            Map<String, List<String>> fieldErrors,
            WebRequest request
    ) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setTitle(title);
        body.setInstance(URI.create(
                ((ServletWebRequest) request).getRequest().getRequestURI()
        ));
        body.setProperty("code", code);
        body.setProperty("fieldErrors", fieldErrors);

        String requestId = (String) request.getAttribute(
                RequestIdFilter.ATTRIBUTE_NAME,
                RequestAttributes.SCOPE_REQUEST
        );
        body.setProperty("requestId", requestId);

        return body;
    }
}
