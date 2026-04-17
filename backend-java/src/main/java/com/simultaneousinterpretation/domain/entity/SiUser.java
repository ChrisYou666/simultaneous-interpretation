package com.simultaneousinterpretation.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体
 * <p>
 * 对应数据库 si_user 表
 * 表结构: id, username, password_hash, role, created_at
 *
 * @author System
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码哈希
     */
    private String passwordHash;

    /**
     * 角色
     */
    private String role;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 验证是否为管理员角色
     *
     * @return true 表示是管理员
     */
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(this.role);
    }

    /**
     * 验证是否为普通用户角色
     *
     * @return true 表示是普通用户
     */
    public boolean isNormalUser() {
        return "USER".equalsIgnoreCase(this.role);
    }

    /**
     * 验证用户是否启用（默认为true，因为表中没有status字段）
     *
     * @return true 表示启用
     */
    public boolean isEnabled() {
        return true;
    }
}
