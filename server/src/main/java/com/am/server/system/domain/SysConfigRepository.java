package com.am.server.system.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysConfigRepository extends JpaRepository<SysConfig, String> {

    List<SysConfig> findAllByCategory(String category);
}
