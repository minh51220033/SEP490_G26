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
                .andExpect(content().string(containsString("phase1TransitionRulesCard")))
                .andExpect(content().string(containsString("phase1EquipmentsCard")))
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

        // 2. Tạo master data qua API (mặc định PENDING_VERIFICATION / PENDING_REVIEW) -> xác nhận DB & GET reload
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

        // 3. Phân công BS-Phòng & BS-Dịch vụ -> PENDING_REVIEW -> Duyệt thủ công -> ACTIVE
        mockMvc.perform(post("/admin/api/resources/branches/" + b1.getBranchId() + "/doctors/" + doc.getStaffId() + "/rooms")
                        .session(adminSession).with(csrf())
                        .param("roomId", r1.getRoomId().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        Long roomAssignId = roomAssignmentRepository
                .findByBranchBranchIdAndStaffStaffIdOrderByAssignmentIdAsc(b1.getBranchId(), doc.getStaffId())
                .get(0).getAssignmentId();

        // Non-ADMIN không được phép duyệt hoặc gọi API ngoài phạm vi chi nhánh
        mockMvc.perform(post("/admin/api/resources/doctors/room-assignments/" + roomAssignId + "/approve")
                        .session(nonAdminSession).with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/api/resources/branches/" + b2.getBranchId() + "/equipments")
                        .session(nonAdminSession))
                .andExpect(status().isForbidden());

        // ADMIN duyệt thủ công -> ACTIVE
        mockMvc.perform(post("/admin/api/resources/doctors/room-assignments/" + roomAssignId + "/approve")
                        .session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
