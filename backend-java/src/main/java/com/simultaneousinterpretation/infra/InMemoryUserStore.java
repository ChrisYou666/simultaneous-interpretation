package com.simultaneousinterpretation.infra;

import com.simultaneousinterpretation.domain.SiUser;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内存用户存储。
 *
 * 从配置文件读取内置用户清单（支持环境变量覆盖），替代原来的 MySQL si_user 表。
 * 应用启动时自动初始化，无需数据库迁移。
 *
 * 默认用户（可通过环境变量覆盖）：
 * <pre>
 * ADMIN_USERNAME=admin  ADMIN_PASSWORD=admin123  ADMIN_ROLE=admin
 * USER_USERNAME=user    USER_PASSWORD=user123    USER_ROLE=user
 * </pre>
 */
@Component
public class InMemoryUserStore {

  private static final Logger log = LoggerFactory.getLogger(InMemoryUserStore.class);

  private final Map<String, SiUser> users = new ConcurrentHashMap<>();

  public InMemoryUserStore(
      @Value("${app.auth.admin-username:admin}") String adminUsername,
      @Value("${app.auth.admin-password:admin123}") String adminPassword,
      @Value("${app.auth.admin-role:admin}") String adminRole,
      @Value("${app.auth.user-username:user}") String userUsername,
      @Value("${app.auth.user-password:user123}") String userPassword,
      @Value("${app.auth.user-role:user}") String userRole
  ) {
    users.put(adminUsername, new SiUser(1L, adminUsername, adminPassword, adminRole));
    users.put(userUsername, new SiUser(2L, userUsername, userPassword, userRole));
  }

  @PostConstruct
  public void init() {
    log.info("[Auth] 内存用户初始化完成，共 {} 个用户: {}",
        users.size(), users.keySet());
  }

  public Optional<SiUser> findByUsername(String username) {
    return Optional.ofNullable(users.get(username));
  }
}
