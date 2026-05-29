package com.am.server.domain.git;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface GitCommitFileRepository extends JpaRepository<GitCommitFile, Long> {

    List<GitCommitFile> findByCommitIdOrderBySortOrderAsc(Long commitId);

    long countByCommitId(Long commitId);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM GitCommitFile f WHERE f.commitId = :commitId")
    void deleteByCommitId(@Param("commitId") Long commitId);
}
