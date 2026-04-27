package com.simultaneousinterpretation.api;

import com.simultaneousinterpretation.common.Result;
import com.simultaneousinterpretation.facade.AuthFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 * <p>
 * 处理用户登录、Token 验证等认证相关请求
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthFacade authFacade;

    /**
     * 用户登录
     *
     * @param request 登录请求
     * @return 登录结果
     */
    @PostMapping("/login")
    public Result<AuthFacade.LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("收到登录请求，username={}", request.username());
        long startTime = System.currentTimeMillis();

        try {
            Result<AuthFacade.LoginResponse> result = authFacade.login(request.username(), request.password());
            
            log.info("登录请求完成，username={}, code={}, 耗时={}ms", 
                     request.username(), result.getCode(), System.currentTimeMillis() - startTime);
            
            return result;
        } catch (Exception e) {
            log.error("登录请求异常，username={}, 耗时={}ms", 
                     request.username(), System.currentTimeMillis() - startTime, e);
            return Result.error(9001, "登录失败，请稍后再试");
        }
    }

    /**
     * 验证 Token
     *
     * @param token JWT Token
     * @return 验证结果
     */
    @GetMapping("/validate")
    public Result<AuthFacade.UserSessionResponse> validateToken(
            @RequestHeader(value = "Authorization", required = false) String token) {
        log.debug("收到Token验证请求");
        long startTime = System.currentTimeMillis();

        try {
            Result<AuthFacade.UserSessionResponse> result = authFacade.validateToken(token);
            
            log.debug("Token验证完成，code={}, 耗时={}ms", 
                     result.getCode(), System.currentTimeMillis() - startTime);
            
            return result;
        } catch (Exception e) {
            log.error("Token验证异常，耗时={}ms", System.currentTimeMillis() - startTime, e);
            return Result.error(9001, "Token验证失败");
        }
    }

    /**
     * 获取当前用户信息
     *
     * @param token JWT Token
     * @return 用户信息
     */
    @GetMapping("/current")
    public Result<AuthFacade.UserSessionResponse> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String token) {
        log.debug("获取当前用户信息请求");
        long startTime = System.currentTimeMillis();

        try {
            // 先验证 Token
            Result<AuthFacade.UserSessionResponse> validateResult = authFacade.validateToken(token);
            if (!validateResult.isSuccess()) {
                return validateResult;
            }

            String username = validateResult.getData().username();
            Result<AuthFacade.UserSessionResponse> result = authFacade.getCurrentUser(username);
            
            log.debug("获取当前用户信息完成，username={}, code={}, 耗时={}ms", 
                     username, result.getCode(), System.currentTimeMillis() - startTime);
            
            return result;
        } catch (Exception e) {
            log.error("获取当前用户信息异常，耗时={}ms", System.currentTimeMillis() - startTime, e);
            return Result.error(9001, "获取用户信息失败");
        }
    }

    /**
     * 登录请求
     */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {}
}
