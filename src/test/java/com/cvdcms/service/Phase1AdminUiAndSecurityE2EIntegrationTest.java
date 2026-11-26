package com.cvdcms.service;

import com.cvdcms.DTO.RoomDTO;
import com.cvdcms.DTO.ServiceCreateDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class Phase1AdminUiAndSecurityE2EIntegrationTest {

    @Autowired private WebApplicationContext wac;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomService roomService;
    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private ServiceCategoryRepository serviceCategoryRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private UserRepository userRepository;
    @PersistenceContext private EntityManager em;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ClinicEquipmentRepository equipmentRepository;
    @Autowired private DoctorRoomAssignmentRepository roomAssignmentRepository;
    @Autowired private DoctorServicePermissionRepository servicePermissionRepository;
    @Autowired private StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private User createUserWithRole(String prefix, String roleName, Branch branch) {
        long seq = System.nanoTime();
        User u = new User();
        u.setUsername(prefix + "_" + seq);
        u.setPassword("hash");
        u.setEmail(prefix + "_" + seq + "@clinic.local");
        u.setPhoneNumber("09" + String.format("%08d", Math.abs(seq % 100_000_000L)));
        u.setUserType(UserType.STAFF);
        u.setBranch(branch);
        u.setIsActive(true);
        u = userRepository.save(u);

        Role role = roleRepository.findByRoleName(roleName).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(roleName);
            r.setDisplayName(roleName);
            return roleRepository.save(r);
        });
        u.getRoles().add(role);
        u = userRepository.saveAndFlush(u);
        return u;
    }

    private Branch createBranch(String code, String name) {
        Branch b = new Branch();
        b.setBranchCode(code);
        b.setBranchName(name);
        b.setAddress("123 Test");
        b.setPhone("0901112223");
        b.setIsActive(true);
        return branchRepository.save(b);
    }

    @Test
    @DisplayName("E2E Admin UI: Render 3 trang /admin/branches/{id}, /admin/services/{id}/edit, /admin/staffs/{id} & luồng CRUD + DB persistence + RBAC/BranchScope")
    @WithMockUser(username = "admin_e2e", roles = {"ADMIN"})
    void adminUiPagesAndEndToEndMasterDataWorkflow() throws Exception {
        Branch b1 = createBranch("BR-E2E-1", "Cơ sở E2E 1");
        Branch b2 = createBranch("BR-E2E-2", "Cơ sở E2E 2");

        User adminUser = createUserWithRole("admin_e2e", "ADMIN", b1);
        User nonAdminUser = createUserWithRole("rec_e2e", "RECEPTION", b1);

        RoomDTO rDto1 = new RoomDTO();
        rDto1.setRoomCode("P101");
        rDto1.setRoomName("Phòng 101");
        rDto1.setRoomType(RoomType.EXAM);
        rDto1.setFloorLabel("Tầng 1");
        rDto1.setZoneLabel("Khu A");
        rDto1.setFloorOrder(1);
        Room r1 = roomService.createRoom(b1.getBranchId(), rDto1);

        RoomDTO rDto2 = new RoomDTO();
        rDto2.setRoomCode("P201");
        rDto2.setRoomName("Phòng 201");
        rDto2.setRoomType(RoomType.TREATMENT);
        rDto2.setFloorLabel("Tầng 2");
        rDto2.setFloorOrder(2);
        Room r2 = roomService.createRoom(b1.getBranchId(), rDto2);

        ServiceCategory cat = new ServiceCategory();
        cat.setCategoryCode("CAT-E2E-" + System.nanoTime());
        cat.setCategoryName("Khám E2E");
        cat.setCategoryType(ServiceType.CONSULTATION);
        cat = serviceCategoryRepository.save(cat);

        ServiceCreateDTO svcDto = new ServiceCreateDTO();
        svcDto.setCategoryId(cat.getCategoryId());
        svcDto.setServiceCode("SVC-E2E-" + Math.abs(System.nanoTime() % 1_000_000L));
        svcDto.setServiceName("Khám tổng quát E2E");
        svcDto.setServiceType(ServiceType.CONSULTATION);
        svcDto.setUnit("Lần");
        svcDto.setBasePrice(new BigDecimal("120000"));
        svcDto.setSurchargeAmount(BigDecimal.ZERO);
        svcDto.setRequiresTooth(false);
        svcDto.setIsBookableOnline(true);
        svcDto.setPreparationDurationMinutes(2);
        svcDto.setProcedureDurationMinutes(6);
        svcDto.setTurnoverDurationMinutes(2);
        svcDto.setStatus(ServiceStatus.ACTIVE);
        serviceCatalogService.createService(svcDto);
        Service svc = serviceRepository.findByServiceCode(svcDto.getServiceCode()).orElseThrow();

        User docUser = createUserWithRole("doc_e2e", "DOCTOR", b1);
        StaffProfile doc = new StaffProfile();
        doc.setUser(docUser);
        doc.setStaffCode("DOC" + Math.abs(System.nanoTime() % 1_000_000_000L));
        doc.setFullName("BS E2E");
        doc = staffProfileRepository.save(doc);

        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("loggedInUser", adminUser);
        adminSession.setAttribute("roleNames", List.of("ADMIN"));

        MockHttpSession nonAdminSession = new MockHttpSession();
        nonAdminSession.setAttribute("loggedInUser", nonAdminUser);
        nonAdminSession.setAttribute("roleNames", List.of("RECEPTION"));

        // 1. Render 3 trang Admin UI & xác nhận có đủ DOM Phase 1 + không hỏng trang cũ
        mockMvc.perform(get("/admin/branches/" + b1.getBranchId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("phase1TransitionRulesCard"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("phase1EquipmentsCard"))))
                .andExpect(content().string(containsString("roomFloorLabel")))
                .andExpect(content().string(containsString("Phòng 101")));

        mockMvc.perform(get("/admin/services/" + svc.getServiceId() + "/edit").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("preparationDurationMinutes")))
                .andExpect(content().string(containsString("procedureDurationMinutes")))
                .andExpect(content().string(containsString("turnoverDurationMinutes")))
                .andExpect(content().string(containsString("phase1ServiceReqCard")));

        mockMvc.perform(get("/admin/staffs/" + doc.getStaffId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tab-resource-perms")))
                .andExpect(content().string(containsString("pane-resource-perms")))
                .andExpect(content().string(containsString("BS E2E")));

        // 2. Tạo master data qua API -> xác nhận DB & GET reload
        mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/transition-rules")
                        .session(adminSession).with(csrf())
                        .param("ruleScope", "SPECIFIC_PAIR")
                        .param("fromRoomId", r1.getRoomId().toString())
                        .param("toRoomId", r2.getRoomId().toString())
                        .param("travelBufferMinutes", "7"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/admin/api/resources/branches/" + b1.getBranchId() + "/transition-rules").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].travelBufferMinutes").value(7));

        MvcResult eqRes = mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/equipments")
                        .session(adminSession).with(csrf())
                        .param("equipmentCode", "EQ-E2E-1")
                        .param("equipmentName", "Máy X-quang E2E")
                        .param("equipmentType", "XRAY")
                        .param("allocationMode", "FIXED_ROOM")
                        .param("fixedRoomId", r1.getRoomId().toString())
                        .param("deviceSerialNumber", "SN-E2E-" + System.nanoTime()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andReturn();

        Long eqId = equipmentRepository.findByBranchBranchIdOrderByEquipmentCodeAsc(b1.getBranchId()).get(0).getEquipmentId();
        assertEquals(EquipmentStatus.PENDING_VERIFICATION, equipmentRepository.findById(eqId).orElseThrow().getStatus());

        mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/equipments/" + eqId + "/verify")
                        .session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 3. Phân công BS-Phòng -> mặc định ACTIVE ngay khi Admin cấp quyền
        mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/doctors/" + doc.getStaffId() + "/rooms")
                        .session(adminSession).with(csrf())
                        .param("roomId", r1.getRoomId().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Long roomAssignId = roomAssignmentRepository
                .findByBranchBranchIdAndStaffStaffIdOrderByAssignmentIdAsc(b1.getBranchId(), doc.getStaffId())
                .get(0).getAssignmentId();

        // Non-ADMIN không được phép thu hồi hoặc gọi API ngoài phạm vi chi nhánh
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/api/resources/doctors/room-assignments/" + roomAssignId)
                        .session(nonAdminSession).with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/api/resources/branches/" + b2.getBranchId() + "/equipments")
                        .session(nonAdminSession))
                .andExpect(status().isForbidden());

        // 4. Dropdown APIs + Duplicate rejection (409) + Revoke (DELETE -> INACTIVE)
        mockMvc.perform(get("/admin/api/resources/branches/" + b1.getBranchId() + "/rooms")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roomCode").exists());

        mockMvc.perform(get("/admin/api/resources/branches/" + b2.getBranchId() + "/rooms")
                        .session(nonAdminSession))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/api/resources/services")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceCode").exists());

        // Trùng phân công phòng -> 409 Conflict
        mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/doctors/" + doc.getStaffId() + "/rooms")
                        .session(adminSession).with(csrf())
                        .param("roomId", r1.getRoomId().toString()))
                .andExpect(status().isConflict());

        // Cấp quyền dịch vụ -> trùng 409 -> duyệt -> thu hồi INACTIVE
        mockMvc.perform(post("/admin/api/resources/doctors/" + doc.getStaffId() + "/services")
                        .session(adminSession).with(csrf())
                        .param("serviceId", svc.getServiceId().toString())
                        .param("note", "Test perm"))
                .andExpect(status().isCreated());

        Long permId = servicePermissionRepository
                .findByStaffStaffIdOrderByPermissionIdAsc(doc.getStaffId())
                .get(0).getPermissionId();

        mockMvc.perform(post("/admin/api/resources/doctors/" + doc.getStaffId() + "/services")
                        .session(adminSession).with(csrf())
                        .param("serviceId", svc.getServiceId().toString()))
                .andExpect(status().isConflict());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/api/resources/doctors/room-assignments/" + roomAssignId)
                        .session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/api/resources/doctors/service-permissions/" + permId)
                        .session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    @DisplayName("Role-based #tab-resource-perms & API security: chỉ Bác sĩ có tab, non-doctor bị ẩn & chặn API, đổi role giữ nguyên lịch sử")
    @WithMockUser(username = "admin_role_test", roles = {"ADMIN"})
    void roleBasedResourceTabAndApiSecurityVerification() throws Exception {
        Branch b1 = createBranch("BR-ROLE-1", "Cơ sở Role 1");
        User adminUser = createUserWithRole("admin_rt", "ADMIN", b1);

        RoomDTO rDto = new RoomDTO();
        rDto.setRoomCode("PR101");
        rDto.setRoomName("Phòng Role 101");
        rDto.setRoomType(RoomType.EXAM);
        Room r1 = roomService.createRoom(b1.getBranchId(), rDto);

        ServiceCategory cat = new ServiceCategory();
        cat.setCategoryCode("CAT-RT-" + System.nanoTime());
        cat.setCategoryName("Khám Role");
        cat.setCategoryType(ServiceType.CONSULTATION);
        cat = serviceCategoryRepository.save(cat);

        ServiceCreateDTO svcDto = new ServiceCreateDTO();
        svcDto.setCategoryId(cat.getCategoryId());
        svcDto.setServiceCode("SVC-RT-" + Math.abs(System.nanoTime() % 1_000_000L));
        svcDto.setServiceName("Khám Role Test");
        svcDto.setServiceType(ServiceType.CONSULTATION);
        svcDto.setUnit("Lần");
        svcDto.setBasePrice(new BigDecimal("100000"));
        svcDto.setSurchargeAmount(BigDecimal.ZERO);
        svcDto.setRequiresTooth(false);
        svcDto.setIsBookableOnline(true);
        svcDto.setPreparationDurationMinutes(2);
        svcDto.setProcedureDurationMinutes(6);
        svcDto.setTurnoverDurationMinutes(2);
        svcDto.setStatus(ServiceStatus.ACTIVE);
        serviceCatalogService.createService(svcDto);
        Service svc = serviceRepository.findByServiceCode(svcDto.getServiceCode()).orElseThrow();

        Role doctorRole = roleRepository.findByRoleName("BAC_SI").orElseThrow();
        Role recRole = roleRepository.findByRoleName("LE_TAN").orElseThrow();

        StaffProfile doc = createStaffWithBranchRole("bs_rt", "BAC_SI", "BS Role Test", b1);
        StaffProfile rec = createStaffWithBranchRole("lt_rt", "LE_TAN", "LT Role Test", b1);
        StaffProfile acc = createStaffWithBranchRole("kt_rt", "KE_TOAN", "KT Role Test", b1);
        StaffProfile inv = createStaffWithBranchRole("kho_rt", "QUAN_LY_KHO", "Kho Role Test", b1);

        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("loggedInUser", adminUser);
        adminSession.setAttribute("roleNames", List.of("ADMIN"));

        MockHttpSession docSession = new MockHttpSession();
        docSession.setAttribute("loggedInUser", doc.getUser());
        docSession.setAttribute("roleNames", List.of("BAC_SI"));

        MockHttpSession recSession = new MockHttpSession();
        recSession.setAttribute("loggedInUser", rec.getUser());
        recSession.setAttribute("roleNames", List.of("LE_TAN"));

        // 1. Admin mở hồ sơ Bác sĩ -> thấy tab và cấu hình được
        mockMvc.perform(get("/admin/staffs/" + doc.getStaffId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tab-resource-perms")))
                .andExpect(content().string(containsString("pane-resource-perms")));

        // 2-4. Admin mở hồ sơ Lễ tân, Kế toán, Quản lý kho -> không thấy tab
        for (StaffProfile nonDoc : List.of(rec, acc, inv)) {
            mockMvc.perform(get("/admin/staffs/" + nonDoc.getStaffId()).session(adminSession))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("tab-resource-perms"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("pane-resource-perms"))));

            // Admin gọi trực tiếp API cấp quyền cho nhân viên không phải Bác sĩ -> 403 Forbidden
            mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/doctors/" + nonDoc.getStaffId() + "/rooms")
                            .session(adminSession).with(csrf())
                            .param("roomId", r1.getRoomId().toString()))
                    .andExpect(status().isForbidden());
        }

        // Admin cấp quyền & duyệt cho Bác sĩ
        mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/doctors/" + doc.getStaffId() + "/rooms")
                        .session(adminSession).with(csrf())
                        .param("roomId", r1.getRoomId().toString()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/admin/api/resources/doctors/" + doc.getStaffId() + "/services")
                        .session(adminSession).with(csrf())
                        .param("serviceId", svc.getServiceId().toString()))
                .andExpect(status().isCreated());

        // 5. Bác sĩ tự xem được phân công của mình (200 OK), nhưng không tự cấp quyền được (403); Lễ tân gọi API bị 403
        mockMvc.perform(get("/admin/api/resources/branches/" + b1.getBranchId() + "/doctors/" + doc.getStaffId() + "/rooms")
                        .session(docSession))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/api/resources/doctors/" + doc.getStaffId() + "/services")
                        .session(docSession))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/api/resources/doctors/" + doc.getStaffId() + "/services")
                        .session(docSession).with(csrf())
                        .param("serviceId", svc.getServiceId().toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/api/resources/branches/" + b1.getBranchId() + "/doctors/" + doc.getStaffId() + "/rooms")
                        .session(recSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/resources/doctors/" + doc.getStaffId() + "/services")
                        .session(recSession))
                .andExpect(status().isForbidden());

        // 6. Đổi nhân viên từ Bác sĩ sang Lễ tân -> tab biến mất, API bị 403, dữ liệu phân công vẫn được giữ nguyên trong DB
        StaffBranchAssignment sba = staffBranchAssignmentRepository
                .findByStaffStaffIdAndStatus(doc.getStaffId(), AssignmentStatus.ACTIVE).get(0);
        sba.setRole(recRole);
        staffBranchAssignmentRepository.saveAndFlush(sba);

        mockMvc.perform(get("/admin/staffs/" + doc.getStaffId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("tab-resource-perms"))));

        mockMvc.perform(get("/admin/api/resources/doctors/" + doc.getStaffId() + "/services")
                        .session(adminSession))
                .andExpect(status().isForbidden());

        assertFalse(roomAssignmentRepository.findByBranchBranchIdAndStaffStaffIdOrderByAssignmentIdAsc(
                b1.getBranchId(), doc.getStaffId()).isEmpty());
        assertFalse(servicePermissionRepository.findByStaffStaffIdOrderByPermissionIdAsc(
                doc.getStaffId()).isEmpty());

        // Đổi lại thành Bác sĩ -> tab hiện lại và dữ liệu cũ vẫn còn nguyên
        sba.setRole(doctorRole);
        staffBranchAssignmentRepository.saveAndFlush(sba);
        mockMvc.perform(get("/admin/staffs/" + doc.getStaffId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tab-resource-perms")));
    }

    private StaffProfile createStaffWithBranchRole(String prefix, String roleName, String fullName, Branch branch) {
        User u = createUserWithRole(prefix, roleName, branch);
        StaffProfile sp = new StaffProfile();
        sp.setUser(u);
        sp.setStaffCode("STF" + Math.abs(System.nanoTime() % 1_000_000_000L));
        sp.setFullName(fullName);
        sp.setStatus(StaffStatus.ACTIVE);
        sp = staffProfileRepository.saveAndFlush(sp);

        Role role = roleRepository.findByRoleName(roleName).orElseThrow();
        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(role);
        sba.setStatus(AssignmentStatus.ACTIVE);
        sba.setIsPrimary(true);
        sba.setStartDate(java.time.LocalDate.now().minusDays(1));
        staffBranchAssignmentRepository.saveAndFlush(sba);
        return sp;
    }
}
