package dev.jchat.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final MessageSource messages;

    GlobalExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    record FieldErrorItem(String field, String message, String code) {
    }

    // 404
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Object> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        String code = safeCode(ex.getMessage(), "error.not_found");
        return build(HttpStatus.NOT_FOUND, code, msg(code), List.of(), req);
    }

    // Доменные 422/400
    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<Object> handleDomainValidation(DomainValidationException ex, HttpServletRequest req) {
        String code = ex.getCode();
        String m = msg(code, ex.getArgs());
        var list = new ArrayList<FieldErrorItem>();
        if (ex.getField() != null) list.add(new FieldErrorItem(ex.getField(), m, code));
        return build(ex.getStatus(), code, m, list, req);
    }

    // Нарушение уникальности → 409 (конкретику определим по constraintName, если доступно)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        String code = "error.constraint_violation";
        String message = msg(code);
        String constraint = extractConstraintName(ex);
        if ("uk_users_email".equalsIgnoreCase(constraint)) {
            code = "error.email_taken";
            message = msg(code);
        } else if ("uk_users_kc_id".equalsIgnoreCase(constraint)) {
            code = "error.kc_id_taken";
            message = msg(code);
        }
        return build(HttpStatus.CONFLICT, code, message, List.of(), req);
    }

    // 400: @Valid ошибок
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var list = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorItem(fe.getField(), resolveFieldMessage(fe.getDefaultMessage()), fe.getCode()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "error.validation", msg("error.validation"), list, getReq(request));
    }

    @ExceptionHandler(org.springframework.validation.BindException.class)
    public ResponseEntity<Object> onBind(org.springframework.validation.BindException ex,
                                         jakarta.servlet.http.HttpServletRequest req) {
        var list = ex.getFieldErrors().stream()
                .map(fe -> new FieldErrorItem(
                        fe.getField(),
                        resolveFieldMessage(fe.getDefaultMessage()),
                        fe.getCode()))
                .toList();

        return build(HttpStatus.BAD_REQUEST, "error.validation", msg("error.validation"), list, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        var list = ex.getConstraintViolations().stream()
                .map(cv -> new FieldErrorItem(pathOf(cv), msg(cv.getMessage()), cv.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "error.validation", msg("error.validation"), list, req);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "error.bad_request", msg("error.bad_request"), List.of(), getReq(request));
    }

    @Override
    protected ResponseEntity<Object> handleErrorResponseException(
            org.springframework.web.ErrorResponseException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        HttpStatus st = HttpStatus.resolve(status.value());
        if (st == null) st = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = "error." + st.value();

        String detail = null;
        // попробуем взять detail из ProblemDetail, если есть
        var body = ex.getBody();
        if (body.getDetail() != null && !body.getDetail().isBlank()) {
            detail = body.getDetail();
        }
        if (detail == null) {
            // твоя локализация/MessageSource
            detail = msg(code);
        }

        return build(st, code, detail, List.of(), getReq(request));
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGeneric(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal", msg("error.internal"), List.of(), req);
    }

    // === utils ===
    private ResponseEntity<Object> build(HttpStatus status, String code, String message,
                                         List<FieldErrorItem> errors, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setTitle(status.getReasonPhrase());
        if (req != null) {
            try {
                pd.setInstance(URI.create(req.getRequestURI()));
            } catch (Exception ignored) {
            }
        }
        var now = Instant.now();
        pd.setProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(now));
        pd.setProperty("epochMillis", now.toEpochMilli());
        if (code != null && !code.isBlank()) {
            pd.setProperty("code", code);
            try {
                pd.setType(URI.create("urn:error:%s".formatted(code)));
            } catch (Exception ignored) {
            }
        }
        String traceId = MDC.get("traceId");
        if (traceId != null) pd.setProperty("traceId", traceId);
        if (errors != null && !errors.isEmpty()) pd.setProperty("errors", errors);
        return ResponseEntity.status(status).body(pd);
    }

    private String msg(String code, Object... args) {
        var locale = LocaleContextHolder.getLocale();
        return messages.getMessage(code, args, code, locale);
    }

    private String resolveFieldMessage(String defaultMessage) {
        if (defaultMessage == null) return null;
        if (defaultMessage.matches("[a-zA-Z0-9_.-]+")) return msg(defaultMessage);
        return defaultMessage;
    }

    private static HttpServletRequest getReq(WebRequest request) {
        return (HttpServletRequest) request.resolveReference(WebRequest.REFERENCE_REQUEST);
    }

    private static String pathOf(jakarta.validation.ConstraintViolation<?> cv) {
        var it = cv.getPropertyPath().iterator();
        String last = null;
        while (it.hasNext()) last = it.next().getName();
        return last != null ? last : cv.getPropertyPath().toString();
    }

    private static String safeCode(String s, String fallback) {
        return (s != null && s.matches("[A-Za-z0-9_.-]+")) ? s : fallback;
    }

    private static String extractConstraintName(DataIntegrityViolationException ex) {
        // Не привязываемся жёстко к драйверу; если Postgres — попытаемся вытащить имя констрейнта
        Throwable t = ex.getMostSpecificCause();
        try {
            var method = t.getClass().getMethod("getServerErrorMessage");
            Object em = method.invoke(t);
            if (em != null) {
                var cm = em.getClass().getMethod("getConstraint");
                Object c = cm.invoke(em);
                if (c != null) return c.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
