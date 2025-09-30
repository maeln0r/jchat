package dev.jchat.identity.util;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

public final class Tracing {
    private Tracing() {
    }

    public static String currentTraceId() {
        SpanContext ctx = Span.current().getSpanContext();
        return ctx == null || !ctx.isValid() ? "" : ctx.getTraceId();
    }
}