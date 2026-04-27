package com.simultaneousinterpretation.facade;

import com.simultaneousinterpretation.common.BizException;
import com.simultaneousinterpretation.common.Result;
import com.simultaneousinterpretation.domain.enums.ErrorCode;
import com.simultaneousinterpretation.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 认证门面
 * <p>
 * 编排认证相关业务逻辑，提供统一的响应结构
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthFacade {

    private final AuthService authService;

    /**
     * 用户登录
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录结果
     */
    public Result<LoginResponse> login(String username, String password) {
        log.info("登录请求，username={}", username);
        long startTime = System.currentTimeMillis();

        try {
            AuthService.LoginResult loginResult = authService.login(username, password);

            LoginResponse response = new LoginResponse(
                    loginResult.token(),
                    loginResult.username(),
                    loginResult.role(),
                    loginResult.userId()
            );

            log.info("登录成功，username={}, role={}, 耗时={}ms", 
                     username, loginResult.role(), System.currentTimeMillis() - startTime);

            return Result.success(response);
        } catch (BizException e) {
            log.warn("登录业务异常，username={}, errorCode={}, message={}", 
                     username, e.getCode(), e.getMessage());
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("登录系统异常，username={}, error={}", username, e.getMessage(), e);
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 验证 Token
     *
     * @param token JWT Token
     * @return 验证结果
     */
    public Result<UserSessionResponse> validateToken(String token) {
        log.debug("Token验证请求");
        long startTime = System.currentTimeMillis();

        try {
            var session = authService.validateToken(token);

            UserSessionResponse response = new UserSessionResponse(
                    session.getUserId(),
                    session.getUsername(),
                    session.getRole()
            );

            log.debug("Token验证成功，username={}, 耗时={}ms", 
                     session.getUsername(), System.currentTimeMillis() - startTime);

            return Result.success(response);
        } catch (BizException e) {
            log.warn("Token验证业务异常，errorCode={}, message={}", e.getCode(), e.getMessage());
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Token验证系统异常，error={}", e.getMessage(), e);
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 获取当前用户信息
     *
     * @param username 用户名
     * @return 用户信息
     */
    public Result<UserSessionResponse> getCurrentUser(String username) {
        log.debug("获取当前用户信息，username={}", username);
        long startTime = System.currentTimeMillis();

        try {
            var session = authService.getCurrentUser(username);

            UserSessionResponse response = new UserSessionResponse(
                    session.getUserId(),
                    session.getUsername(),
                    session.getRole()
            );

            log.debug("获取用户信息成功，username={}, 耗时={}ms", 
                     username, System.currentTimeMillis() - startTime);

            return Result.success(response);
        } catch (BizException e) {
            log.warn("获取用户信息业务异常，username={}, errorCode={}, message={}", 
                     username, e.getCode(), e.getMessage());
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("获取用户信息系统异常，username={}, error={}", username, e.getMessage(), e);
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 登录响应
     */
    public record LoginResponse(
            String token,
            String username,
            String role,
            Long userId
    ) {}

    /**
     * 用户会话响应
     */
    public record UserSessionResponse(
            Long userId,
            String username,
            String role
    ) {}
}
