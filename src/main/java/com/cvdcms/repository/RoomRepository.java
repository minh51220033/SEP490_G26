package com.cvdcms.repository;

import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.RoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByBranchBranchIdOrderByRoomCodeAsc(Long branchId);

    List<Room> findByBranchBranchIdAndStatusOrderByRoomCodeAsc(Long branchId, RoomStatus status);

    boolean existsByBranchBranchIdAndRoomCode(Long branchId, String roomCode);

    boolean existsByBranchBranchIdAndRoomCodeAndRoomIdNot(Long branchId, String roomCode, Long roomId);

    long countByBranchBranchId(Long branchId);
}
