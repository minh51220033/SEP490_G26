package com.cvdcms.repository;

import com.cvdcms.entity.Token;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.TokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TokenRepository extends JpaRepository<Token, Long> {

    // Tìm token dựa trên mã OTP và User (Dùng cho chức năng xác nhận OTP)
    Optional<Token> findByTokenAndUser(String token, User user);

    // Tìm token dựa trên chuỗi token thuần
    Optional<Token> findByToken(String token);

    // Tìm danh sách token theo userId, loại token và trạng thái chưa sử dụng (dùng cho REFRESH token)
    List<Token> findByUser_UserIdAndTokenTypeAndIsUsedFalseOrderByCreatedAtDesc(Long userId, TokenType tokenType);

    // Tìm token theo chuỗi token và loại token
    Optional<Token> findByTokenAndTokenType(String token, TokenType tokenType);
}
