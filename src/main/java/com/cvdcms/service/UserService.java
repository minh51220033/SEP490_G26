package com.cvdcms.service;

import com.cvdcms.entity.User;
import java.util.List;

public interface UserService {
    // ... (Giữ nguyên các hàm cũ của bạn như findByEmail, save, v.v.) ...

    /**
     * Che giấu một phần email để bảo mật thông tin
     * Ví dụ: nguyenvana@gmail.com -> ng********@gmail.com
     */
    String maskEmail(String email);
}
