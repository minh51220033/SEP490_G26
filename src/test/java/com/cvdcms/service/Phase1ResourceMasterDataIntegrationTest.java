package com.cvdcms.service;

import com.cvdcms.DTO.RoomDTO;
import com.cvdcms.DTO.ServiceCreateDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class Phase1ResourceMasterDataIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomService roomService;
    @Autowired private BranchService branchService;
    @Autowired private BranchTransitionRuleService transitionRuleService;
    @Autowired private ClinicEquipmentService equipmentService;
    @Autowired private EquipmentTransferService transferService;
    @Autowired private ResourceAssignmentAdminService assignmentAdminService;
    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private UserRepository userRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private Branch createBranch(String code, String name) {
        Branch b = new Branch();
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setAddress("123 Đường Test");
        b.setPhone("0900000001");
        b.setIsActive(true);
        return branchRepository.save(b);
    }

    private Room createRoom(Branch branch, String code, String name, String floorLabel, int floorOrder) {
        RoomDTO dto = new RoomDTO();
        dto.setRoomCode(code);
        dto.setRoomName(name);
        dto.setRoomType(RoomType.TREATMENT);
        dto.setFloorLabel(floorLabel);
        dto.setFloorOrder(floorOrder);
        return roomService.createRoom(branch.getBranchId(), dto);
    }

    @Test
    @DisplayName("Transition rules cho phép nhiều SPECIFIC_PAIR cùng chi nhánh, chống trùng và resolve đúng thứ tự ưu tiên")
    void transitionRulesAllowMultipleSpecificPairsAndEnforceUniqueness() {
        Branch b1 = createBranch("BR-TR1", "Cơ sở Transition");
        Room r1 = createRoom(b1, "P101", "Phòng 101", "Tầng 1", 1);
        Room r2 = createRoom(b1, "P102", "Phòng 102", "Tầng 2", 2);
        Room r3 = createRoom(b1, "P103", "Phòng 103", "Tầng 1", 1);

        transitionRuleService.createRule(b1.getBranchId(), TransitionRuleScope.SAME_FLOOR_DIFF_ROOM, null, null, null, null, 2);
        transitionRuleService.createRule(b1.getBranchId(), TransitionRuleScope.DIFF_FLOOR, null, null, null, null, 6);
        transitionRuleService.createRule(b1.getBranchId(), TransitionRuleScope.SPECIFIC_PAIR, r1.getRoomId(), r2.getRoomId(), null, null, 10);
        transitionRuleService.createRule(b1.getBranchId(), TransitionRuleScope.SPECIFIC_PAIR, r1.getRoomId(), r3.getRoomId(), null, null, 4);

        // Chống trùng rule cùng scope hoặc cùng cặp phòng
        assertThrows(IllegalArgumentException.class, () ->
                transitionRuleService.createRule(b1.getBranchId(), TransitionRuleScope.DIFF_FLOOR, null, null, null, null, 8));
        assertThrows(IllegalArgumentException.class, () ->
                transitionRuleService.createRule(b1.getBranchId(), TransitionRuleScope.SPECIFIC_PAIR, r1.getRoomId(), r2.getRoomId(), null, null, 12));

        // Kiểm tra thứ tự ưu tiên: SPECIFIC_PAIR > DIFF_FLOOR > SAME_FLOOR_DIFF_ROOM
        assertEquals(10, transitionRuleService.resolveBufferMinutes(b1.getBranchId(), r1, r2));
        assertEquals(4, transitionRuleService.resolveBufferMinutes(b1.getBranchId(), r1, r3));
        assertEquals(6, transitionRuleService.resolveBufferMinutes(b1.getBranchId(), r3, r2));
        assertEquals(0, transitionRuleService.resolveBufferMinutes(b1.getBranchId(), r1, r1));
    }

    @Test
    @DisplayName("Thiết bị PENDING_VERIFICATION không xuất hiện trong active list; chống trùng serial/code; transfer ghi log")
    void equipmentVerificationAndDuplicateSerialAndTransfer() {
        Branch b1 = createBranch("BR-EQ1", "CS Thiết bị 1");
        Branch b2 = createBranch("BR-EQ2", "CS Thiết bị 2");
        Room p104 = createRoom(b1, "P104", "Phòng X-quang", "Tầng 1", 1);

        ClinicEquipment unverified = equipmentService.createEquipment(
                b1.getBranchId(), "XQ-01", "Máy NanoPix chưa xác minh",
                EquipmentType.XRAY, EquipmentAllocationMode.FIXED_ROOM, p104.getRoomId(),
                "SN-UNIQ-001", EquipmentStatus.PENDING_VERIFICATION);

        assertTrue(equipmentService.getActiveEquipmentsByBranch(b1.getBranchId()).isEmpty(),
                "Thiết bị PENDING_VERIFICATION không được coi là ACTIVE");

        // Chống trùng serial & trùng equipmentCode
        assertThrows(IllegalArgumentException.class, () -> equipmentService.createEquipment(
                b2.getBranchId(), "XQ-02", "Máy trùng serial",
                EquipmentType.XRAY, EquipmentAllocationMode.BRANCH_SHARED, null, "SN-UNIQ-001", null));
        assertThrows(IllegalArgumentException.class, () -> equipmentService.createEquipment(
                b1.getBranchId(), "XQ-01", "Máy trùng mã",
                EquipmentType.XRAY, EquipmentAllocationMode.BRANCH_SHARED, null, "SN-UNIQ-002", null));

        // Xác minh và kích hoạt
        ClinicEquipment activated = equipmentService.verifyAndActivate(b1.getBranchId(), unverified.getEquipmentId(), "SN-UNIQ-001");
        assertEquals(EquipmentStatus.ACTIVE, activated.getStatus());
        assertEquals(1, equipmentService.getActiveEquipmentsByBranch(b1.getBranchId()).size());

        // Điều chuyển sang chi nhánh khác
        Room p201 = createRoom(b2, "P201", "Phòng X-quang CS2", "Tầng 1", 1);
        EquipmentTransferLog log = transferService.transferEquipment(
                activated.getEquipmentId(), b2.getBranchId(), p201.getRoomId(), "Điều chuyển máy sang CS2", 1L);
        assertEquals(EquipmentTransferType.BRANCH_TRANSFER, log.getTransferType());
        assertEquals(b2.getBranchId(), log.getToBranchId());
    }

    private User createTestUser(String prefix, Branch branch) {
        long seq = System.nanoTime();
        User u = new User();
        u.setUsername(prefix + "_" + seq);
        u.setPassword("hash");
        u.setEmail(prefix + "_" + seq + "@clinic.local");
        u.setPhoneNumber("09" + String.format("%08d", Math.abs(seq % 100_000_000L)));
        u.setUserType(UserType.STAFF);
        u.setBranch(branch);
        u.setIsActive(true);
        return userRepository.save(u);
    }

    @Test
    @DisplayName("Phân công BS-Phòng & BS-Dịch vụ ở trạng thái PENDING_REVIEW không được tính là ACTIVE")
    void pendingReviewDoctorAssignmentsExcludedFromActiveQueries() {
        Branch branch = createBranch("BR-DOC1", "CS Bác sĩ");
        Room room = createRoom(branch, "P105", "Phòng Khám 105", "Tầng 1", 1);

        User user = createTestUser("doc_p1", branch);

        StaffProfile staff = new StaffProfile();
        staff.setUser(user);
        staff.setStaffCode("BS" + Math.abs(System.nanoTime() % 1_000_000_000L));
        staff.setFullName("BS Phase 1");
        staff = staffProfileRepository.save(staff);

        ServiceCategory cat = new ServiceCategory();
        cat.setCategoryCode("CAT-P1-" + System.nanoTime());
        cat.setCategoryName("Khám");
        cat.setCategoryType(ServiceType.CONSULTATION);
        cat = serviceCategoryRepository.save(cat);

        ServiceCreateDTO svcDto = new ServiceCreateDTO();
        svcDto.setCategoryId(cat.getCategoryId());
        svcDto.setServiceCode("SVC-P1-" + System.nanoTime());
        svcDto.setServiceName("Khám nhanh 10 phút");
        svcDto.setServiceType(ServiceType.CONSULTATION);
        svcDto.setUnit("Lần");
        svcDto.setBasePrice(new BigDecimal("100000"));
        svcDto.setSurchargeAmount(BigDecimal.ZERO);
        svcDto.setRequiresTooth(false);
        svcDto.setIsBookableOnline(true);
        svcDto.setPreparationDurationMinutes(2);
        svcDto.setProcedureDurationMinutes(5);
        svcDto.setTurnoverDurationMinutes(3);
        svcDto.setStatus(ServiceStatus.ACTIVE);
        serviceCatalogService.createService(svcDto);

        Service savedSvc = serviceRepository.findByServiceCode(svcDto.getServiceCode()).orElseThrow();
        assertEquals(10, savedSvc.getEstimatedDurationMinutes(), "Tổng 3 pha (2+5+3=10p < 15p) phải đồng bộ vào estimatedDurationMinutes");

        DoctorRoomAssignment roomAssn = assignmentAdminService.assignDoctorRoom(
                branch.getBranchId(), staff.getStaffId(), room.getRoomId(), true, LocalDate.now(), null, DoctorAssignmentStatus.PENDING_REVIEW);
        DoctorServicePermission svcPerm = assignmentAdminService.grantDoctorServicePermission(
                staff.getStaffId(), savedSvc.getServiceId(), branch.getBranchId(), LocalDate.now(), null, DoctorServicePermissionStatus.PENDING_REVIEW, "Chờ duyệt");

        assertTrue(assignmentAdminService.getActiveRoomAssignments(branch.getBranchId(), staff.getStaffId()).isEmpty());
        assertTrue(assignmentAdminService.getActiveServicePermissions(savedSvc.getServiceId()).isEmpty());

        assignmentAdminService.approveRoomAssignment(roomAssn.getAssignmentId());
        assignmentAdminService.approveServicePermission(svcPerm.getPermissionId());

        assertEquals(1, assignmentAdminService.getActiveRoomAssignments(branch.getBranchId(), staff.getStaffId()).size());
        assertEquals(1, assignmentAdminService.getActiveServicePermissions(savedSvc.getServiceId()).size());
    }

    @Test
    @DisplayName("BranchService không fallback all-active cho nhân viên chưa gán chi nhánh & API Master Data kiểm tra branch scope + CSRF")
    @WithMockUser(username = "admin_test", roles = {"ADMIN"})
    void branchScopeAndMasterDataApiSecurity() throws Exception {
        Branch b1 = createBranch("BR-SEC1", "CS Bảo mật 1");
        Branch b2 = createBranch("BR-SEC2", "CS Bảo mật 2");

        User staffUser = createTestUser("staff_unassigned", null);

        assertTrue(branchService.getUserAccessibleBranches(staffUser.getUserId()).isEmpty(),
                "Nhân viên chưa được gán chi nhánh không được fallback sang toàn bộ chi nhánh");
        assertFalse(branchService.canUserAccessBranch(staffUser.getUserId(), b2.getBranchId()));

        // Cố gọi API chi nhánh b2 bằng session user chỉ có quyền b1 -> 403
        User scopedUser = createTestUser("scoped_user", b1);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", scopedUser);

        mockMvc.perform(get("/admin/api/resources/branches/" + b2.getBranchId() + "/equipments").session(session))
                .andExpect(status().isForbidden());

        // Thiếu CSRF khi POST -> 403
        mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/transition-rules")
                        .session(session)
                        .param("ruleScope", "SAME_FLOOR_DIFF_ROOM")
                        .param("travelBufferMinutes", "3"))
                .andExpect(status().isForbidden());

        // Hợp lệ với CSRF trên đúng chi nhánh b1 -> 201
        mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/transition-rules")
                        .session(session)
                        .with(csrf())
                        .param("ruleScope", "SAME_FLOOR_DIFF_ROOM")
                        .param("travelBufferMinutes", "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.travelBufferMinutes").value(3));
    }
}
