package com.spring_ai.practice2.chatbot.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@EnableConfigurationProperties(AnonymousVisitorProperties.class)
public class AnonymousVisitorFilter extends OncePerRequestFilter {

    public static final String VISITOR_ID_ATTRIBUTE = "visitorId";

    private final AnonymousVisitorProperties properties;

    public AnonymousVisitorFilter(AnonymousVisitorProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/chat");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String visitorId = findVisitorId(request).orElseGet(() -> createVisitorId(response));
        request.setAttribute(VISITOR_ID_ATTRIBUTE, visitorId);
        filterChain.doFilter(request, response);
    }

    private Optional<String> findVisitorId(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }

        return Arrays.stream(request.getCookies())
                .filter(cookie -> properties.cookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(this::isUuid)
                .findFirst();
    }

    private String createVisitorId(HttpServletResponse response) {
        String visitorId = UUID.randomUUID().toString();
        ResponseCookie cookie = ResponseCookie.from(properties.cookieName(), visitorId)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.cookieMaxAge())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return visitorId;
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
