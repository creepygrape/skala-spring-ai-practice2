package com.spring_ai.practice2.chatbot.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatbot.anonymous")
public record AnonymousVisitorProperties(
        String cookieName,
        Duration cookieMaxAge,
        boolean secure) {
}
