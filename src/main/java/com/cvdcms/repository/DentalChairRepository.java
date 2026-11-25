package com.cvdcms.repository;

import com.cvdcms.entity.DentalChair;
import com.cvdcms.entity.enums.ChairStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DentalChairRepository extends JpaRepository<DentalChair, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT c FROM DentalChair c WHERE c.chairId = :chairId")
    Optional<DentalChair> findByIdForUpdate(@Param("chairId") Long chairId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT c FROM DentalChair c WHERE c.chairId IN :chairIds ORDER BY c.chairId ASC")
    List<DentalChair> findByIdInForUpdateOrderByChairIdAsc(@Param("chairIds") Collection<Long> chairIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT c FROM DentalChair c WHERE c.room.roomId = :roomId AND c.status NOT IN :excludedStatuses ORDER BY c.chairId ASC")
    List<DentalChair> findCandidatesByRoomIdForUpdateOrderByChairIdAsc(
            @Param("roomId") Long roomId,
            @Param("excludedStatuses") Collection<ChairStatus> excludedStatuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT c FROM DentalChair c WHERE c.room.roomId IN :roomIds AND c.status NOT IN :excludedStatuses ORDER BY c.chairId ASC")
    List<DentalChair> findCandidatesByRoomIdsForUpdateOrderByChairIdAsc(
            @Param("roomIds") Collection<Long> roomIds,
            @Param("excludedStatuses") Collection<ChairStatus> excludedStatuses);

    List<DentalChair> findByBranchBranchIdOrderByChairCodeAsc(Long branchId);

    List<DentalChair> findByRoomRoomIdOrderByChairCodeAsc(Long roomId);

    List<DentalChair> findByBranchBranchIdAndStatusOrderByChairCodeAsc(Long branchId, ChairStatus status);

    boolean existsByBranchBranchIdAndChairCode(Long branchId, String chairCode);

    boolean existsByBranchBranchIdAndChairCodeAndChairIdNot(Long branchId, String chairCode, Long chairId);

    long countByBranchBranchId(Long branchId);

    long countByBranchBranchIdAndStatus(Long branchId, ChairStatus status);

    long countByRoomRoomId(Long roomId);
}
