package com.cvdcms.repository;

import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.RoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DispatchRoomRepository extends JpaRepository<Room, Long> {
    List<Room> findByBranchBranchIdAndStatusOrderByRoomCode(Long branch, RoomStatus status);
}