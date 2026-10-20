package com.cvdcms.DTO;

import com.cvdcms.entity.enums.RoomStatus;
import com.cvdcms.entity.enums.RoomType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoomDTO {

    private String roomCode;

    private String roomName;

    private RoomType roomType = RoomType.TREATMENT;

    private RoomStatus status = RoomStatus.ACTIVE;
}
