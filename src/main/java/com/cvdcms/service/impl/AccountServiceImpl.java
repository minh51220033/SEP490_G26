package com.cvdcms.service.impl;

import com.cvdcms.DTO.AccountCreateDTO;
import com.cvdcms.DTO.AccountUpdateDTO;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.repository.RoleRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.AccountService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountServiceImpl implements AccountService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public AccountServiceImpl(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }


    // =========================================================
    // 1. TÌM KIẾM + LỌC + PHÂN TRANG
    // =========================================================

    @Override
    @Transactional(readOnly = true)
    public Page<User> searchAccounts(
            String keyword,
            Integer roleId,
            Boolean active,
            Pageable pageable
    ) {

        if (keyword == null) {
            keyword = "";
        }

        keyword = keyword.trim();

        return userRepository.searchUsers(
                keyword,
                roleId,
                active,
                pageable
        );
    }


    // =========================================================
    // 2. XEM CHI TIẾT ACCOUNT
    // =========================================================

    @Override
    @Transactional(readOnly = true)
    public User getAccountById(Long userId) {

        return userRepository.findById(userId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Không tìm thấy tài khoản có ID: " + userId
                        )
                );
    }


    // =========================================================
    // 3. TẠO ACCOUNT
    // =========================================================

    @Override
    public User createAccount(AccountCreateDTO dto) {

        // Kiểm tra username
        if (dto.getUsername() == null ||
                dto.getUsername().trim().isEmpty()) {

            throw new RuntimeException(
                    "Username không được để trống."
            );
        }

        String username = dto.getUsername().trim();

        // Username phải duy nhất
        if (userRepository.existsByUsername(username)) {

            throw new RuntimeException(
                    "Username đã tồn tại."
            );
        }


        // Kiểm tra password
        if (dto.getPassword() == null ||
                dto.getPassword().isBlank()) {

            throw new RuntimeException(
                    "Password không được để trống."
            );
        }


        // Confirm password
        if (!dto.getPassword().equals(dto.getConfirmPassword())) {

            throw new RuntimeException(
                    "Mật khẩu xác nhận không khớp."
            );
        }


        // Kiểm tra role
        if (dto.getRoleId() == null) {

            throw new RuntimeException(
                    "Vui lòng chọn role."
            );
        }

        Role role = roleRepository.findById(dto.getRoleId())
                .orElseThrow(() ->
                        new RuntimeException(
                                "Role không tồn tại."
                        )
                );


        // Tạo User
        User user = new User();

        user.setUsername(username);

        // KHÔNG lưu password dạng plain text
        user.setPassword(
                passwordEncoder.encode(dto.getPassword())
        );

        user.setEmail(dto.getEmail());

        user.setPhoneNumber(dto.getPhoneNumber());

        user.setFullName(dto.getFullName());

        user.setRole(role);

        user.setIsActive(true);


        return userRepository.save(user);
    }


    // =========================================================
    // 4. UPDATE ACCOUNT
    // =========================================================

    @Override
    public User updateAccount(
            Long userId,
            AccountUpdateDTO dto
    ) {

        User user = getAccountById(userId);

        if (dto.getEmail() != null) {
            user.setEmail(dto.getEmail().trim());
        }

        if (dto.getPhoneNumber() != null) {
            user.setPhoneNumber(dto.getPhoneNumber().trim());
        }

        if (dto.getFullName() != null) {
            user.setFullName(dto.getFullName().trim());
        }

        return userRepository.save(user);
    }


    // =========================================================
    // 5. ĐỔI ROLE
    // =========================================================

    @Override
    public void changeRole(
            Long userId,
            Integer roleId
    ) {

        User user = getAccountById(userId);

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Role không tồn tại."
                        )
                );

        user.setRole(role);

        userRepository.save(user);
    }


    // =========================================================
    // 6. KHÓA / MỞ KHÓA ACCOUNT
    // =========================================================

    @Override
    public void toggleStatus(Long userId) {

        User user = getAccountById(userId);

        Boolean currentStatus = user.getIsActive();

        if (currentStatus == null) {
            user.setIsActive(true);
        } else {
            user.setIsActive(!currentStatus);
        }

        userRepository.save(user);
    }
}