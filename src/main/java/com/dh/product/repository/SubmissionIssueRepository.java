package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.SubmissionIssue;

public interface SubmissionIssueRepository extends JpaRepository<SubmissionIssue, Long> {
    List<SubmissionIssue> findBySubmissionIdOrderByIdAsc(Long submissionId);

    void deleteBySubmissionId(Long submissionId);
}
