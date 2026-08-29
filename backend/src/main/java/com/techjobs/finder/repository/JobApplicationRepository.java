package com.techjobs.finder.repository;

import com.techjobs.finder.entity.JobApplication;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {

    Optional<JobApplication> findByUserIdAndJobId(Long userId, Long jobId);

    @EntityGraph(attributePaths = {"job", "job.company", "job.source"})
    List<JobApplication> findByUserIdOrderByCreatedAtDesc(Long userId);
}
