package com.cvdcms.repository;

import com.cvdcms.entity.Token;
import com.cvdcms.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TokenRepository extends JpaRepository<Token, Long> {

    // Tìm token dựa trên mã OTP và User (Dùng cho chức năng xác nhận OTP)
    Optional<Token> findByTokenAndUser(String token, User user);

    // Tìm token dựa trên chuỗi token thuần
    Optional<Token> findByToken(String token);
}
