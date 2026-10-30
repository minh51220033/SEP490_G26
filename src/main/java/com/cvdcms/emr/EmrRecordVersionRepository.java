package com.cvdcms.emr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmrRecordVersionRepository extends JpaRepository<EmrRecordVersion, Long> {
    Optional<EmrRecordVersion> findByRecord_RecordIdAndVersionNumber(Long recordId, Integer versionNumber);

    @Query("""
        select distinct v from EmrRecordVersion v
        left join fetch v.snapshot
        left join fetch v.documents
        where v.record.recordId = :recordId
        order by v.versionNumber desc
        """)
    List<EmrRecordVersion> findAllDetailsByRecordId(@Param("recordId") Long recordId);
}
