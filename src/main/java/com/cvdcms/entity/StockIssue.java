package com.cvdcms.entity;

import com.cvdcms.entity.enums.StockIssueType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "stock_issues",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_si_code", columnNames = "issue_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class StockIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "issue_id")
    private Long issueId;

    @Column(name = "issue_code", nullable = false, length = 20, unique = true)
    private String issueCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 20)
    private StockIssueType issueType = StockIssueType.INTERNAL_USE;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_branch_id")
    private Branch destinationBranch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issuer_id", nullable = false)
    private StaffProfile issuer;

    @Column(name = "notes", length = 255)
    private String notes;

    @OneToMany(mappedBy = "issue", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockIssueItem> items = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (this.issueCode == null || this.issueCode.isEmpty()) {
            this.issueCode = "PX" + System.currentTimeMillis();
        }
    }
}
