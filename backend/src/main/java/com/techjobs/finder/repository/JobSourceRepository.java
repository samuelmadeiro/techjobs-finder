package com.techjobs.finder.repository;

import com.techjobs.finder.entity.JobSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobSourceRepository extends JpaRepository<JobSource, Long> {

    Optional<JobSource> findByCode(String code);

    List<JobSource> findByEnabledTrue();
}
