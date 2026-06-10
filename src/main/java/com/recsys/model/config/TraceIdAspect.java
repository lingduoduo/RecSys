package com.recsys.model.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Aspect
@Component
public class TraceIdAspect {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Around("execution(* com.recsys..*(..))")
    public Object addTraceId(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean set = false;
        try {
            if (MDC.get(TRACE_ID_KEY) == null) {
                MDC.put(TRACE_ID_KEY, resolveTraceId());
                set = true;
            }
            propagateToResponse();
            return joinPoint.proceed();
        } finally {
            if (set) {
                MDC.remove(TRACE_ID_KEY);
            }
        }
    }

    /** Prefers the upstream X-Trace-Id header; generates a fresh UUID as fallback. */
    private static String resolveTraceId() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String header = request.getHeader(TRACE_ID_HEADER);
                if (header != null && !header.isBlank()) {
                    return header.trim();
                }
            }
        } catch (Exception ignored) {}
        return UUID.randomUUID().toString();
    }

    /** Echoes the traceId back in the response header so clients can correlate logs. */
    private static void propagateToResponse() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletResponse response = attrs.getResponse();
                String traceId = MDC.get(TRACE_ID_KEY);
                if (response != null && traceId != null) {
                    response.setHeader(TRACE_ID_HEADER, traceId);
                }
            }
        } catch (Exception ignored) {}
    }
}
