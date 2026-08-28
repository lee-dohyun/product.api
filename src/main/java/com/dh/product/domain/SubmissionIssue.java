package com.dh.product.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 사유를 자유 텍스트가 아니라 code + message 로 나눠 담는다 - 코드로 두면 나중에 자동 검사로
// 승격하거나 반려 사유 통계를 낼 수 있다. field 는 폼이 인라인으로 표시하기 위한 것이다.
@Entity
@Table(name = "submission_issues")
@Getter
@Setter
@NoArgsConstructor
public class SubmissionIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private ProductSubmission submission;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(length = 100)
    private String field;

    @Column(nullable = false, length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IssueSeverity severity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public SubmissionIssue(ProductSubmission submission, String code, String field, String message,
            IssueSeverity severity) {
        this.submission = submission;
        this.code = code;
        this.field = field;
        this.message = message;
        this.severity = severity;
    }
}
