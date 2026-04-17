package com.simultaneousinterpretation.mapper;

import com.simultaneousinterpretation.domain.entity.SiUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户数据访问层
 * <p>
 * 负责用户数据的 CRUD 操作
 * 注意：si_user 表实际只有 id, username, password_hash, role, created_at 五个字段
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@Repository
public class UserMapper {

    private static final RowMapper<SiUser> ROW_MAPPER = (rs, rowNum) -> SiUser.builder()
            .id(rs.getLong("id"))
            .username(rs.getString("username"))
            .passwordHash(rs.getString("password_hash"))
            .role(rs.getString("role"))
            .createdAt(rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime()
                    : null)
            .build();

    private final JdbcTemplate jdbcTemplate;

    public UserMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户信息
     */
    public Optional<SiUser> selectByUsername(String username) {
        log.debug("根据用户名查询用户, username={}", username);
        String sql = "SELECT id, username, password_hash, role, created_at FROM si_user WHERE username = ?";
        List<SiUser> result = jdbcTemplate.query(sql, ROW_MAPPER, username);
        return result.stream().findFirst();
    }

    /**
     * 根据用户ID查询用户
     *
     * @param userId 用户ID
     * @return 用户信息
     */
    public Optional<SiUser> selectById(Long userId) {
        log.debug("根据用户ID查询用户, userId={}", userId);
        String sql = "SELECT id, username, password_hash, role, created_at FROM si_user WHERE id = ?";
        List<SiUser> result = jdbcTemplate.query(sql, ROW_MAPPER, userId);
        return result.stream().findFirst();
    }

    /**
     * 统计用户总数
     *
     * @return 用户数量
     */
    public long count() {
        String sql = "SELECT COUNT(*) FROM si_user";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * 插入用户
     *
     * @param user 用户信息
     * @return 影响行数
     */
    public int insert(SiUser user) {
        log.info("插入用户, username={}, role={}", user.getUsername(), user.getRole());
        String sql = "INSERT INTO si_user (username, password_hash, role) VALUES (?, ?, ?)";
        return jdbcTemplate.update(sql,
                user.getUsername(),
                user.getPasswordHash(),
                user.getRole());
    }

    /**
     * 更新用户信息（仅更新存在的字段）
     *
     * @param user 用户信息
     * @return 影响行数
     */
    public int updateById(SiUser user) {
        log.info("更新用户信息, userId={}", user.getId());
        String sql = "UPDATE si_user SET username = ?, role = ? WHERE id = ?";
        return jdbcTemplate.update(sql,
                user.getUsername(),
                user.getRole(),
                user.getId());
    }

    /**
     * 重置密码
     *
     * @param username    用户名
     * @param newPassword 新密码哈希
     * @return 影响行数
     */
    public int resetPassword(String username, String newPassword) {
        log.info("重置密码, username={}", username);
        String sql = "UPDATE si_user SET password_hash = ? WHERE username = ?";
        return jdbcTemplate.update(sql, newPassword, username);
    }

    /**
     * 删除用户
     *
     * @param userId 用户ID
     * @return 影响行数
     */
    public int deleteById(Long userId) {
        log.info("删除用户, userId={}", userId);
        String sql = "DELETE FROM si_user WHERE id = ?";
        return jdbcTemplate.update(sql, userId);
    }

    /**
     * 查询所有用户
     *
     * @return 用户列表
     */
    public List<SiUser> selectAll() {
        log.debug("查询所有用户");
        String sql = "SELECT id, username, password_hash, role, created_at FROM si_user ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }
}
