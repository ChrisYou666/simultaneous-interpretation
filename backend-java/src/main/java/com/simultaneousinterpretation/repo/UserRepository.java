package com.simultaneousinterpretation.repo;

import com.simultaneousinterpretation.domain.SiUser;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

  private static final RowMapper<SiUser> ROW =
      (rs, i) ->
          new SiUser(
              rs.getLong("id"),
              rs.getString("username"),
              rs.getString("password_hash"),
              rs.getString("role"));

  private final JdbcTemplate jdbc;

  public UserRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<SiUser> findByUsername(String username) {
    var list =
        jdbc.query(
            "SELECT id, username, password_hash, role FROM si_user WHERE username = ?",
            ROW,
            username);
    return list.stream().findFirst();
  }

  public long count() {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM si_user", Long.class);
    return n != null ? n : 0;
  }

  public void insert(String username, String passwordHash, String role) {
    jdbc.update(
        "INSERT INTO si_user (username, password_hash, role) VALUES (?,?,?)",
        username,
        passwordHash,
        role);
  }

  public void resetPassword(String username, String newPassword) {
    jdbc.update("UPDATE si_user SET password_hash = ? WHERE username = ?", newPassword, username);
  }
}
