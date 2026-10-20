package com.cvdcms.DTO;

import com.cvdcms.entity.enums.ChairStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DentalChairDTO {

    private Long roomId;

    private String chairCode;

    private String chairName;

    private ChairStatus status = ChairStatus.AVAILABLE;
}
