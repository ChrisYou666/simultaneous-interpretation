package com.simultaneousinterpretation.service;

import com.simultaneousinterpretation.common.BizException;
import com.simultaneousinterpretation.common.Constants;
import com.simultaneousinterpretation.domain.enums.ErrorCode;
import com.simultaneousinterpretation.domain.vo.UserSessionVo;
import com.simultaneousinterpretation.security.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务（内存认证，无数据库依赖）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtService jwtService;

    private static final Map<String, String> USERS = new ConcurrentHashMap<>();
    static {
        USERS.put("admin", "admin123");
        USERS.put("user", "user123");
    }

    public LoginResult login(String username, String password) {
        log.info("登录请求，username={}", username);
        validateLoginParams(username, password);

        String stored = USERS.get(username);
        if (stored == null || !stored.equals(password)) {
            log.warn("登录失败，username={}", username);
            throw new BizException(ErrorCode.AUTH_FAILED, Constants.MSG_LOGIN_FAILED);
        }

        String role = "admin".equals(username) ? "ADMIN" : "USER";
        String token = jwtService.generateToken(username, role);
        log.info("登录成功，username={}, role={}", username, role);
        return new LoginResult(token, username, role, 1L);
    }

    public UserSessionVo validateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "Token 不能为空");
        }
        if (token.startsWith(Constants.TOKEN_PREFIX)) {
            token = token.substring(Constants.TOKEN_PREFIX.length());
        }
        try {
            Claims claims = jwtService.parseAndValidate(token);
            String username = claims.getSubject();
            String role = claims.get("role", String.class);
            if (username == null) {
                throw new BizException(ErrorCode.TOKEN_INVALID, "Token 无效");
            }
            return UserSessionVo.builder()
                    .username(username)
                    .role(role != null ? role : "USER")
                    .token(token)
                    .build();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Token 验证异常，error={}", e.getMessage());
            throw new BizException(ErrorCode.TOKEN_INVALID, Constants.MSG_TOKEN_EXPIRED);
        }
    }

    public UserSessionVo getCurrentUser(String username) {
        if (!USERS.containsKey(username)) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        String role = "admin".equals(username) ? "ADMIN" : "USER";
        return UserSessionVo.builder()
                .username(username)
                .role(role)
                .build();
    }

    private void validateLoginParams(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new BizException(ErrorCode.PARAM_EMPTY, "用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new BizException(ErrorCode.PARAM_EMPTY, "密码不能为空");
        }
    }

    public record LoginResult(String token, String username, String role, Long userId) {}
}
