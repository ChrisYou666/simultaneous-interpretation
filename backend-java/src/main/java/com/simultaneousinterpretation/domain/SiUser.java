package com.simultaneousinterpretation.domain;

public record SiUser(long id, String username, String passwordHash, String role) {}
