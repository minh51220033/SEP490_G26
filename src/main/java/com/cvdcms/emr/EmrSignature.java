package com.cvdcms.emr;

import com.cvdcms.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "emr_signatures", indexes = @Index(name = "idx_emr_signature_version", columnList = "version_id"))
@Getter
@Setter
@NoArgsConstructor
public class EmrSignature {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "signature_id")
    private Long signatureId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private EmrRecordVersion version;

    @Enumerated(EnumType.STRING)
    @Column(name = "signer_type", nullable = false, length = 20)
    private EmrSignerType signerType;

    @Column(name = "signer_name", nullable = false, length = 150)
    private String signerName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signer_user_id")
    private User signerUser;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "external_transaction_id", length = 100)
    private String externalTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmrSignatureStatus status;

    @Column(name = "signature_hash", nullable = false, length = 64)
    private String signatureHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;
}
