package com.cvdcms.service.impl;


import com.cvdcms.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    // ... (Giữ nguyên các hàm cũ của bạn) ...

    @Override
    public String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "";
        }

        int atIndex = email.indexOf("@");

        // Nếu tên email quá ngắn (1-2 ký tự), chỉ hiển thị ký tự đầu
        if (atIndex <= 2) {
            return email.charAt(0) + "****" + email.substring(atIndex);
        }

        // Giữ lại 2 ký tự đầu, phần còn lại trước @ biến thành dấu *
        String prefix = email.substring(0, 2);
        String domain = email.substring(atIndex);
        return prefix + "********" + domain;
    }
}