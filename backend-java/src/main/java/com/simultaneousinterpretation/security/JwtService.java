package com.simultaneousinterpretation.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private final SecretKey key;
  private final long expirationMs;

  public JwtService(
      @Value("${app.jwt.secret:si-dev-secret-change-in-production-min-32-chars!!}") String secret,
      @Value("${app.jwt.expiration-hours:72}") long expirationHours) {
    byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
    byte[] keyBytes = Arrays.copyOf(raw, 32);
    this.key = Keys.hmacShaKeyFor(keyBytes);
    this.expirationMs = expirationHours * 3600_000L;
  }

  public String generateToken(String username, String role) {
    Date now = new Date();
    return Jwts.builder()
        .subject(username)
        .claim("role", role)
        .issuedAt(now)
        .expiration(new Date(now.getTime() + expirationMs))
        .signWith(key)
        .compact();
  }

  public Claims parseAndValidate(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
