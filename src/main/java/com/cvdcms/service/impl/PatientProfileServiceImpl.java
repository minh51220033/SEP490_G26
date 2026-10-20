
package com.cvdcms.service.impl;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.PatientProfileRepository;

import com.cvdcms.service.PatientProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class PatientProfileServiceImpl implements PatientProfileService {

    private final PatientProfileRepository patientProfileRepository;
    private final BranchRepository branchRepository;

    public PatientProfileServiceImpl(
            PatientProfileRepository patientProfileRepository,
            BranchRepository branchRepository) {
        this.patientProfileRepository = patientProfileRepository;
        this.branchRepository = branchRepository;
    }

    /**
     * Tạo hồ sơ bệnh nhân mặc định.
     * Không cho phép tạo thêm hồ sơ mặc định nếu đã có hồ sơ mặc định.
     */
    @Override
    @Transactional
    public PatientProfile createDefaultProfile(
            User user,
            String fullName,
            LocalDate dob,
            String gender,
            String phoneNumber,
            String address,
            Long branchId) {

        validateUser(user);

        if (patientProfileRepository
                .existsByUser_UserIdAndIsDefaultTrue(user.getUserId())) {
            throw new IllegalStateException(
                    "Tài khoản đã có hồ sơ bệnh nhân mặc định.");
        }

        validateBasicInformation(fullName, gender, address, dob);

        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập số điện thoại.");
        }

        Branch branch = getActiveBranch(branchId);

        PatientProfile profile = new PatientProfile();
        profile.setUser(user);
        profile.setFullName(fullName.trim());
        profile.setDob(dob);
        profile.setGender(gender.trim());
        profile.setPhoneNumber(phoneNumber.trim());
        profile.setAddress(address.trim());
        profile.setPreferredBranch(branch);

        profile.setHasUnderlyingDisease(false);
        profile.setUnderlyingDiseaseDetails(null);
        profile.setAvatarUrl(null);

        profile.setIsDefault(true);
        profile.setStatus("ACTIVE");
        profile.setMedicalHistoryCompleted(false);

        return patientProfileRepository.save(profile);
    }

    /**
     * Tạo hồ sơ bệnh nhân mới.
     * Hồ sơ mới không trở thành mặc định nếu tài khoản đã có hồ sơ.
     */
    @Override
    @Transactional
    public PatientProfile createProfile(
            User user,
            String fullName,
            LocalDate dob,
            String gender,
            String phoneNumber,
            String address,
            Long branchId,
            Boolean hasUnderlyingDisease,
            String underlyingDiseaseDetails,
            String avatarUrl) {

        validateUser(user);
        validateBasicInformation(fullName, gender, address, dob);

        // Nếu không truyền số điện thoại, sử dụng số của tài khoản.
        String profilePhone = phoneNumber;

        if (profilePhone == null || profilePhone.isBlank()) {
            profilePhone = user.getPhoneNumber();
        }

        if (profilePhone == null || profilePhone.isBlank()) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập số điện thoại.");
        }

        boolean hasDisease =
                Boolean.TRUE.equals(hasUnderlyingDisease);

        String diseaseDetails =
                underlyingDiseaseDetails == null
                        ? ""
                        : underlyingDiseaseDetails.trim();

        if (hasDisease && diseaseDetails.isBlank()) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập thông tin bệnh nền.");
        }

        if (diseaseDetails.length() > 2000) {
            throw new IllegalArgumentException(
                    "Thông tin bệnh nền không được vượt quá 2000 ký tự.");
        }

        Branch branch = getActiveBranch(branchId);

        // Chỉ hồ sơ đầu tiên mới được đặt làm hồ sơ mặc định.
        boolean firstProfile =
                !patientProfileRepository
                        .existsByUser_UserId(user.getUserId());

        PatientProfile profile = new PatientProfile();
        profile.setUser(user);
        profile.setFullName(fullName.trim());
        profile.setDob(dob);
        profile.setGender(gender.trim());
        profile.setPhoneNumber(profilePhone.trim());
        profile.setAddress(address.trim());
        profile.setPreferredBranch(branch);

        profile.setHasUnderlyingDisease(hasDisease);
        profile.setUnderlyingDiseaseDetails(
                hasDisease ? diseaseDetails : null);
        profile.setAvatarUrl(avatarUrl);

        profile.setIsDefault(firstProfile);
        profile.setStatus("ACTIVE");
        profile.setMedicalHistoryCompleted(false);

        return patientProfileRepository.save(profile);
    }

    /**
     * Kiểm tra tài khoản hợp lệ.
     */
    private void validateUser(User user) {
        if (user == null || user.getUserId() == null) {
            throw new IllegalArgumentException(
                    "Tài khoản không hợp lệ.");
        }
    }

    /**
     * Kiểm tra thông tin cơ bản của hồ sơ.
     */
    private void validateBasicInformation(
            String fullName,
            String gender,
            String address,
            LocalDate dob) {

        if (fullName == null
                || fullName.isBlank()
                || fullName.trim().length() > 100) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập họ tên hợp lệ.");
        }

        if (gender == null
                || gender.isBlank()
                || gender.trim().length() > 20) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn giới tính.");
        }

        if (address == null
                || address.isBlank()
                || address.trim().length() > 255) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập địa chỉ.");
        }

        if (dob != null && dob.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                    "Ngày sinh không được lớn hơn ngày hiện tại.");
        }
    }

    /**
     * Lấy chi nhánh đang hoạt động.
     */
    private Branch getActiveBranch(Long branchId) {
        if (branchId == null) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn chi nhánh.");
        }

        return branchRepository.findById(branchId)
                .filter(branch ->
                        Boolean.TRUE.equals(branch.getIsActive()))
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Chi nhánh không tồn tại hoặc đã ngừng hoạt động."));
    }
}
