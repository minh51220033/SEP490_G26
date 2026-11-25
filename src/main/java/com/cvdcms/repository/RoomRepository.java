package com.cvdcms.repository;

import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.RoomStatus;
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
public interface RoomRepository extends JpaRepository<Room, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT r FROM Room r WHERE r.roomId = :roomId")
    Optional<Room> findByIdForUpdate(@Param("roomId") Long roomId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT r FROM Room r WHERE r.roomId IN :roomIds ORDER BY r.roomId ASC")
    List<Room> findByIdInForUpdateOrderByRoomIdAsc(@Param("roomIds") Collection<Long> roomIds);

    List<Room> findByBranchBranchIdOrderByRoomCodeAsc(Long branchId);

    List<Room> findByBranchBranchIdAndStatusOrderByRoomCodeAsc(Long branchId, RoomStatus status);

    boolean existsByBranchBranchIdAndRoomCode(Long branchId, String roomCode);

    boolean existsByBranchBranchIdAndRoomCodeAndRoomIdNot(Long branchId, String roomCode, Long roomId);

    long countByBranchBranchId(Long branchId);
}
