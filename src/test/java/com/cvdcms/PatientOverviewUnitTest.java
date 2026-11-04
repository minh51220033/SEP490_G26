package com.cvdcms;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.patient.PatientAccess;
import com.cvdcms.patient.PatientOverviewDTO;
import com.cvdcms.patient.PatientOverviewService;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.DebtService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PatientOverviewUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PatientProfileRepository patientProfileRepository;

    @Mock
    private DebtService debtService;

    @Test
    @DisplayName("DTO Mapping: Khớp các trường nhân khẩu và 3 chỉ số tài chính")
    void testPatientOverviewDtoMapping() {
        PatientProfile p = new PatientProfile();
        p.setProfileId(101L);
        p.setPatientCode("BN101");
        p.setFullName("Nguyễn Văn A");
        p.setGender("Nam");
        p.setDob(LocalDate.of(1995, 5, 20));
        p.setPhoneNumber("0912345678");
        p.setAddress("Hà Nội");
        p.setCustomerSource("Facebook");
        p.setIdCardNumber("001295000123");
        p.setHealthInsuranceNumber("DN4010123456789");
        p.setAllergyDetails("Dị ứng Penicillin");
        p.setUnderlyingDiseaseDetails("Tiểu đường type 2");

        Branch b = new Branch();
        b.setBranchId(1L);
        b.setBranchName("Cơ sở Cầu Giấy");
        p.setPreferredBranch(b);

        PatientDebtSummaryDTO debt = new PatientDebtSummaryDTO(
                101L, "BN101", "Nguyễn Văn A", "0912345678",
                new BigDecimal("500000"), 1L,
                new BigDecimal("5000000"), new BigDecimal("2000000"), new BigDecimal("3000000"),
                null
        );

        PatientOverviewDTO dto = PatientOverviewDTO.from(p, debt);

        assertNotNull(dto);
        assertEquals("BN101", dto.getCode());
        assertEquals("Nguyễn Văn A", dto.getFullName());
        assertEquals("Facebook", dto.getCustomerSource());
        assertEquals("Dị ứng Penicillin", dto.getAllergyDetails());
        assertEquals("Tiểu đường type 2", dto.getUnderlyingDiseaseDetails());
        assertEquals(0, new BigDecimal("5000000").compareTo(dto.getTotalBilled()), "Phát sinh phải đúng 5.000.000 đ");
        assertEquals(0, new BigDecimal("2000000").compareTo(dto.getTotalPaid()), "Đã thanh toán phải đúng 2.000.000 đ");
        assertEquals(0, new BigDecimal("3000000").compareTo(dto.getTotalDebt()), "Công nợ phải đúng 3.000.000 đ");
        assertEquals(0, new BigDecimal("500000").compareTo(dto.getDepositBalance()), "Số dư tạm ứng phải đúng 500.000 đ");
    }

    @Test
    @DisplayName("Bảo mật: PatientAccess phải chặn 403 khi user không phải Admin và thiếu chi nhánh, không nâng quyền ngầm")
    void testPatientAccessSecurityNoAdminEscalation() {
        User staff = new User();
        staff.setUserId(99L);
        staff.setIsActive(true);
        staff.setStatus("ACTIVE");
        staff.setBranch(null); // Không có chi nhánh

        Role staffRole = new Role();
        staffRole.setRoleId(2L);
        staffRole.setRoleName("LE_TAN");
        staffRole.setIsActive(true);
        staff.setRoles(Set.of(staffRole));

        when(userRepository.findById(99L)).thenReturn(Optional.of(staff));
        when(userRepository.hasActiveAdminRole(99L)).thenReturn(false);
        when(userRepository.hasActivePermission(99L, "APPOINTMENT_MANAGE")).thenReturn(true);

        PatientAccess access = new PatientAccess(userRepository, null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", staff);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> access.require(session));
        assertEquals(403, ex.getStatusCode().value(), "Phải trả về 403 Forbidden thay vì nâng quyền admin ngầm");
    }

    @Test
    @DisplayName("Phân quyền: Quyền ADMIN được xem hồ sơ tổng hợp")
    void testRequireAdminOrManagerSuccess() {
        User admin = new User();
        admin.setUserId(1L);
        admin.setIsActive(true);
        admin.setStatus("ACTIVE");
        Role adminRole = new Role();
        adminRole.setRoleName("ADMIN");
        adminRole.setIsActive(true);
        admin.setRoles(Set.of(adminRole));

        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        PatientOverviewService service = new PatientOverviewService(
                userRepository, patientProfileRepository, null, null, null,
                debtService, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", admin);

        User result = service.requireAdminOrManager(session);
        assertNotNull(result);
        assertEquals(1L, result.getUserId());
    }

    @Test
    @DisplayName("Phân quyền: Chặn tài khoản chưa đăng nhập hoặc bị khóa")
    void testRequireAdminOrManagerUnauthorized() {
        PatientOverviewService service = new PatientOverviewService(
                userRepository, patientProfileRepository, null, null, null,
                debtService, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        MockHttpSession emptySession = new MockHttpSession();
        assertThrows(ResponseStatusException.class, () -> service.requireAdminOrManager(emptySession));
    }
}

