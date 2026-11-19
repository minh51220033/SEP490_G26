package com.cvdcms.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ném ra khi khung giờ khám của bác sĩ đã bị người khác đặt trước (race condition)
 * hoặc không còn khả dụng sau khi kiểm tra lại trong transaction khóa bi quan.
 */
public class SlotAlreadyTakenException extends ResponseStatusException {

    public SlotAlreadyTakenException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
