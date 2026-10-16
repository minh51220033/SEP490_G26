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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

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

        // 1. Kiểm tra DTO đầu vào.
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu tài khoản không hợp lệ.");
        }

        // 2. Kiểm tra và chuẩn hóa username.
        if (dto.getUsername() == null || dto.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username không được để trống.");
        }

        String username = dto.getUsername().trim();

        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username đã tồn tại.");
        }

        // 3. Kiểm tra mật khẩu.
        if (dto.getPassword() == null || dto.getPassword().isBlank()) {
            throw new IllegalArgumentException("Mật khẩu không được để trống.");
        }

        if (!dto.getPassword().equals(dto.getConfirmPassword())) {
            throw new IllegalArgumentException("Mật khẩu xác nhận không khớp.");
        }

        // 4. Kiểm tra email.
        if (dto.getEmail() == null || dto.getEmail().isBlank()) {
            throw new IllegalArgumentException("Email không được để trống.");
        }

        String email = dto.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email đã được sử dụng.");
        }

        // 5. Kiểm tra số điện thoại.
        if (dto.getPhoneNumber() == null || dto.getPhoneNumber().isBlank()) {
            throw new IllegalArgumentException(
                    "Số điện thoại không được để trống."
            );
        }

        String phoneNumber = dto.getPhoneNumber().trim();

        if (userRepository.existsByPhoneNumber(phoneNumber)) {
            throw new IllegalArgumentException(
                    "Số điện thoại đã được sử dụng."
            );
        }

        // 6. Lấy vai trò được Admin chọn.
        // Role ID phải tồn tại trong bảng roles.
        if (dto.getRoleId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn vai trò.");
        }

        Role role = roleRepository.findById(dto.getRoleId())
                .orElseThrow(() ->
                        new IllegalArgumentException("Vai trò không tồn tại.")
                );

        if (!Boolean.TRUE.equals(role.getIsActive())) {
            throw new IllegalArgumentException(
                    "Vai trò này đang bị vô hiệu hóa."
            );
        }

        // 7. Tạo tài khoản mới.
        User user = new User();
        user.setUsername(username);

        // Chỉ lưu mật khẩu đã mã hóa BCrypt.
        user.setPassword(passwordEncoder.encode(dto.getPassword()));

        user.setEmail(email);
        user.setPhoneNumber(phoneNumber);
        user.setIsActive(true);
        user.setStatus("ACTIVE");

        // Mô hình mới: một User có thể có nhiều Role.
        user.getRoles().add(role);

        // Không gọi user.setFullName() vì họ tên thuộc PatientProfile.
        // Không tự tạo PatientProfile ở đây.
        // Tài khoản do Admin tạo chưa bắt buộc có hồ sơ bệnh nhân.

        return userRepository.save(user);
    }



    // =========================================================
    // 4. UPDATE ACCOUNT
    // =========================================================


    @Override
    public User updateAccount(Long userId, AccountUpdateDTO dto) {

        // Lấy tài khoản cần cập nhật.
        User user = getAccountById(userId);

        // Cập nhật email nếu người quản trị gửi giá trị mới.
        if (dto.getEmail() != null) {
            String email = dto.getEmail().trim().toLowerCase();

            if (email.isBlank()) {
                throw new IllegalArgumentException(
                        "Email không được để trống."
                );
            }

            // Chỉ báo trùng nếu email đang thuộc tài khoản khác.
            userRepository.findByEmail(email)
                    .filter(existing -> !existing.getUserId().equals(userId))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException(
                                "Email đã được tài khoản khác sử dụng."
                        );
                    });

            user.setEmail(email);
        }

        // Cập nhật số điện thoại nếu có gửi giá trị mới.
        if (dto.getPhoneNumber() != null) {
            String phone = dto.getPhoneNumber().trim();

            if (phone.isBlank()) {
                throw new IllegalArgumentException(
                        "Số điện thoại không được để trống."
                );
            }

            // Không cho phép hai tài khoản sử dụng cùng số điện thoại.
            userRepository.findByPhoneNumber(phone)
                    .filter(existing -> !existing.getUserId().equals(userId))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException(
                                "Số điện thoại đã được tài khoản khác sử dụng."
                        );
                    });

            user.setPhoneNumber(phone);
        }

        // Không cập nhật fullName tại đây.
        // Việc cập nhật họ tên phải thực hiện qua PatientProfileService.

        return userRepository.save(user);
    }



    // =========================================================
    // 5. ĐỔI ROLE
    // =========================================================


    @Transactional
    public void changeRole(Long userId, Integer roleId) {

        // 1. Tìm tài khoản cần thay đổi quyền
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Tài khoản không tồn tại."
                ));

        // 2. Tìm role mới
        Role selectedRole = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Role không tồn tại."
                ));

        // 3. Không cho phép gán role đã bị vô hiệu hóa
        if (!Boolean.TRUE.equals(selectedRole.getIsActive())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Role này hiện không hoạt động."
            );
        }

        // 4. Xóa toàn bộ role cũ
        user.getRoles().clear();

        // 5. Chỉ gán một role mới
        user.getRoles().add(selectedRole);

        // 6. Lưu thay đổi
        userRepository.save(user);
    }



    // =========================================================
    // 6. KHÓA / MỞ KHÓA ACCOUNT
    // =========================================================


    @Override
    public void toggleStatus(Long userId) {

        // Tìm tài khoản; nếu không tồn tại thì báo lỗi.
        User user = getAccountById(userId);

        // Đảo trạng thái hoạt động.
        // Tài khoản đang hoạt động -> bị khóa.
        // Tài khoản bị khóa -> được mở lại.
        user.setIsActive(!Boolean.TRUE.equals(user.getIsActive()));

        userRepository.save(user);
    }

}