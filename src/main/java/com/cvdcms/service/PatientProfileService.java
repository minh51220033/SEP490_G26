
package com.cvdcms.service;

import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;

import java.time.LocalDate;

public interface PatientProfileService {

    /**
     * Tạo hồ sơ bệnh nhân mặc định cho tài khoản.
     */
    PatientProfile createDefaultProfile(
            User user,
            String fullName,
            LocalDate dob,
            String gender,
            String phoneNumber,
            String address,
            Long branchId
    );

    /**
     * Tạo hồ sơ bệnh nhân.
     * Nếu tài khoản chưa có hồ sơ nào, hồ sơ đầu tiên sẽ là mặc định.
     * Nếu đã có hồ sơ, hồ sơ mới không thay thế hồ sơ mặc định.
     */
    PatientProfile createProfile(
            User user,
            String fullName,
            LocalDate dob,
            String gender,
            String phoneNumber,
            String address,
            Long branchId,
            Boolean hasUnderlyingDisease,
            String underlyingDiseaseDetails,
            String avatarUrl
    );

    record BranchSwitchResult(User user, com.cvdcms.entity.Branch branch,
                              java.util.List<PatientProfile> syncedProfiles,
                              PatientProfile defaultProfile) { }

    /**
     * Cập nhật cơ sở khám ưu tiên của tài khoản và đồng bộ sang toàn bộ hồ sơ bệnh nhân thuộc tài khoản
     * trong cùng một transaction. Không thay đổi lịch hẹn hoặc lượt khám đã phát sinh trước đó.
     */
    default BranchSwitchResult switchUserPreferredBranch(Long userId, Long branchId) {
        throw new UnsupportedOperationException("switchUserPreferredBranch is not implemented");
    }
}
