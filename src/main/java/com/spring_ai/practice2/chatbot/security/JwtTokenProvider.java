package com.spring_ai.practice2.chatbot.security;

import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtTokenProvider {

    private static final String ROLES_CLAIM = "roles";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(decodeSecret(properties.secret()));
    }

    public String createAccessToken(String userId, Collection<? extends GrantedAuthority> authorities) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenValidity());
        List<String> roles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return Jwts.builder()
                .subject(userId)
                .claim(ROLES_CLAIM, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public Authentication getAuthentication(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        List<?> roles = claims.get(ROLES_CLAIM, List.class);
        Collection<GrantedAuthority> authorities = roles == null ? List.of() : roles.stream()
                .map(String::valueOf)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        User principal = User.withUsername(claims.getSubject())
                .password("N/A")
                .authorities(authorities)
                .build();

        return new UsernamePasswordAuthenticationToken(principal, token, authorities);
    }

    private static byte[] decodeSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET 환경 변수가 필요합니다.");
        }

        return Decoders.BASE64.decode(secret);
    }
}
