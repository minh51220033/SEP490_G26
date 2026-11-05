//package com.cvdcms;
//
//import com.cvdcms.DTO.PublicDoctorCardDTO;
//import com.cvdcms.DTO.ServicePromotionViewDTO;
//import com.cvdcms.controller.CustomerPortalController;
//import com.cvdcms.controller.GlobalModelAttributesAdvice;
//import com.cvdcms.controller.NewsController;
//import com.cvdcms.controller.PublicServiceCatalogController;
//import com.cvdcms.controller.UserController;
//import com.cvdcms.entity.Branch;
//import com.cvdcms.entity.NewsArticle;
//import com.cvdcms.entity.PatientProfile;
//import com.cvdcms.entity.Promotion;
//import com.cvdcms.entity.Role;
//import com.cvdcms.entity.Service;
//import com.cvdcms.entity.ServiceCategory;
//import com.cvdcms.entity.StaffBranchAssignment;
//import com.cvdcms.entity.StaffProfile;
//import com.cvdcms.entity.StaffSchedule;
//import com.cvdcms.entity.User;
//import com.cvdcms.entity.WorkShift;
//import com.cvdcms.entity.enums.AssignmentStatus;
//import com.cvdcms.entity.enums.PromotionType;
//import com.cvdcms.entity.enums.ScheduleStatus;
//import com.cvdcms.entity.enums.ServiceStatus;
//import com.cvdcms.entity.enums.ServiceType;
//import com.cvdcms.entity.enums.StaffStatus;
//import com.cvdcms.patientportal.PortalAccess;
//import com.cvdcms.repository.BranchRepository;
//import com.cvdcms.repository.CommentBanRepository;
//import com.cvdcms.repository.CommentLikeRepository;
//import com.cvdcms.repository.CommentRepository;
//import com.cvdcms.repository.NewsArticleRepository;
//import com.cvdcms.repository.PatientProfileRepository;
//import com.cvdcms.repository.PromotionRepository;
//import com.cvdcms.repository.ServiceCategoryRepository;
//import com.cvdcms.repository.ServiceRepository;
//import com.cvdcms.repository.StaffBranchAssignmentRepository;
//import com.cvdcms.repository.StaffProfileRepository;
//import com.cvdcms.repository.StaffScheduleRepository;
//import com.cvdcms.repository.UserRepository;
//import com.cvdcms.service.CustomerPortalService;
//import com.cvdcms.service.ServiceCatalogService;
//import com.cvdcms.service.impl.CustomerPortalServiceImpl;
//import com.cvdcms.service.impl.ServiceCatalogServiceImpl;
//import jakarta.servlet.http.HttpServletResponse;
//import jakarta.validation.Validation;
//import jakarta.validation.Validator;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.mockito.junit.jupiter.MockitoSettings;
//import org.mockito.quality.Strictness;
//import org.springframework.beans.factory.ObjectProvider;
//import org.springframework.data.domain.PageImpl;
//import org.springframework.data.domain.Pageable;
//import org.springframework.mock.web.MockHttpServletRequest;
//import org.springframework.mock.web.MockHttpServletResponse;
//import org.springframework.mock.web.MockHttpSession;
//import org.springframework.test.util.ReflectionTestUtils;
//import org.springframework.ui.ExtendedModelMap;
//import org.springframework.ui.Model;
//import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
//
//import java.math.BigDecimal;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.time.LocalTime;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.Set;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//@MockitoSettings(strictness = Strictness.LENIENT)
//class CustomerPortalTest {
//
//    @Mock
//    private BranchRepository branchRepository;
//    @Mock
//    private ServiceRepository serviceRepository;
//    @Mock
//    private ServiceCategoryRepository categoryRepository;
//    @Mock
//    private PromotionRepository promotionRepository;
//    @Mock
//    private StaffProfileRepository staffProfileRepository;
//    @Mock
//    private StaffBranchAssignmentRepository assignmentRepository;
//    @Mock
//    private StaffScheduleRepository scheduleRepository;
//    @Mock
//    private NewsArticleRepository newsRepository;
//    @Mock
//    private CommentRepository commentRepository;
//    @Mock
//    private CommentLikeRepository commentLikeRepository;
//    @Mock
//    private CommentBanRepository commentBanRepository;
//    @Mock
//    private UserRepository userRepository;
//    @Mock
//    private PatientProfileRepository patientProfileRepository;
//
//    private ServiceCatalogService serviceCatalogService;
//    private CustomerPortalService customerPortalService;
//    private PublicServiceCatalogController publicServiceController;
//    private CustomerPortalController customerPortalController;
//    private NewsController newsController;
//    private UserController userController;
//    private GlobalModelAttributesAdvice globalAdvice;
//    private PortalAccess portalAccess;
//
//    private Branch activeBranch;
//    private ServiceCategory activeCategory;
//    private Service activeService;
//    private StaffProfile activeDoctor;
//    private StaffProfile activeReceptionist;
//    private NewsArticle publishedNews;
//
//    @BeforeEach
//    void setUp() {
//        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
//        serviceCatalogService = new ServiceCatalogServiceImpl(
//                serviceRepository,
//                categoryRepository,
//                validator
//        );
//        customerPortalService = new CustomerPortalServiceImpl(
//                branchRepository,
//                serviceCatalogService,
//                promotionRepository,
//                staffProfileRepository,
//                assignmentRepository,
//                scheduleRepository,
//                newsRepository
//        );
//        publicServiceController = new PublicServiceCatalogController(
//                serviceCatalogService,
//                customerPortalService
//        );
//        customerPortalController = new CustomerPortalController(
//                customerPortalService,
//                serviceCatalogService
//        );
//        newsController = new NewsController(
//                newsRepository,
//                commentRepository,
//                commentLikeRepository,
//                commentBanRepository,
//                branchRepository,
//                userRepository,
//                patientProfileRepository
//        );
//
//        userController = new UserController();
//        ReflectionTestUtils.setField(userController, "userRepository", userRepository);
//        ReflectionTestUtils.setField(userController, "patientProfileRepository", patientProfileRepository);
//        ReflectionTestUtils.setField(userController, "staffProfileRepository", staffProfileRepository);
//        ReflectionTestUtils.setField(userController, "customerPortalService", customerPortalService);
//
//        @SuppressWarnings("unchecked")
//        ObjectProvider<CustomerPortalService> portalProvider = mock(ObjectProvider.class);
//        when(portalProvider.getIfAvailable()).thenReturn(customerPortalService);
//        @SuppressWarnings("unchecked")
//        ObjectProvider<ServiceCatalogService> catalogProvider = mock(ObjectProvider.class);
//        when(catalogProvider.getIfAvailable()).thenReturn(serviceCatalogService);
//        @SuppressWarnings("unchecked")
//        ObjectProvider<PatientProfileRepository> ppProvider = mock(ObjectProvider.class);
//        when(ppProvider.getIfAvailable()).thenReturn(patientProfileRepository);
//
//        globalAdvice = new GlobalModelAttributesAdvice(
//                userRepository,
//                portalProvider,
//                catalogProvider,
//                ppProvider
//        );
//        portalAccess = new PortalAccess(userRepository);
//
//        // Dữ liệu mẫu cơ sở
//        activeBranch = new Branch();
//        activeBranch.setBranchId(1L);
//        activeBranch.setBranchCode("CN01");
//        activeBranch.setBranchName("Cơ sở Cầu Giấy");
//        activeBranch.setAddress("123 Cầu Giấy, Hà Nội");
//        activeBranch.setPhone("0901234567");
//        activeBranch.setEmail("caugiay@cuongvietdental.vn");
//        activeBranch.setOpeningTime(LocalTime.of(8, 0));
//        activeBranch.setClosingTime(LocalTime.of(20, 0));
//        activeBranch.setIsActive(true);
//
//        // Dữ liệu mẫu danh mục & dịch vụ
//        activeCategory = new ServiceCategory();
//        activeCategory.setCategoryId(10L);
//        activeCategory.setCategoryCode("PHUC_HINH");
//        activeCategory.setCategoryName("Phục hình & Răng sứ");
//        activeCategory.setCategoryType(ServiceType.PROCEDURE);
//        activeCategory.setIsActive(true);
//
//        activeService = new Service();
//        activeService.setServiceId(100L);
//        activeService.setCategory(activeCategory);
//        activeService.setServiceCode("DV_SU_ZIRCONIA");
//        activeService.setServiceName("Bọc răng sứ Zirconia chính hãng");
//        activeService.setServiceType(ServiceType.PROCEDURE);
//        activeService.setUnit("Răng");
//        activeService.setBasePrice(new BigDecimal("4000000"));
//        activeService.setSurchargeAmount(BigDecimal.ZERO);
//        activeService.setWarrantyMonths(60);
//        activeService.setIsBookableOnline(true);
//        activeService.setStatus(ServiceStatus.ACTIVE);
//        activeService.setDescription("<p>Phục hình răng sứ thẩm mỹ độ bền cao.</p>");
//
//        // Dữ liệu mẫu bác sĩ (BAC_SI) và lễ tân (LE_TAN)
//        Role doctorRole = new Role();
//        doctorRole.setRoleId(2L);
//        doctorRole.setRoleName("BAC_SI");
//        doctorRole.setIsActive(true);
//
//        User doctorUser = new User();
//        doctorUser.setUserId(20L);
//        doctorUser.setUsername("bs.minhtuan");
//        doctorUser.setIsActive(true);
//        doctorUser.setStatus("ACTIVE");
//        doctorUser.setRoles(Set.of(doctorRole));
//
//        activeDoctor = new StaffProfile();
//        activeDoctor.setStaffId(200L);
//        activeDoctor.setStaffCode("NV_BS01");
//        activeDoctor.setFullName("BS.CKI Phạm Minh Tuấn");
//        activeDoctor.setPositionTitle("Bác sĩ Chuyên khoa I");
//        activeDoctor.setSpecialty("Cấy ghép Implant & Phục hình");
//        activeDoctor.setDepartment("Khoa Lâm sàng");
//        activeDoctor.setPracticeLicenseNo("012345/BYT-CCHN");
//        activeDoctor.setStatus(StaffStatus.ACTIVE);
//        activeDoctor.setUser(doctorUser);
//
//        StaffBranchAssignment docAssign = new StaffBranchAssignment();
//        docAssign.setAssignmentId(1L);
//        docAssign.setStaff(activeDoctor);
//        docAssign.setBranch(activeBranch);
//        docAssign.setRole(doctorRole);
//        docAssign.setIsPrimary(true);
//        docAssign.setStartDate(LocalDate.now().minusMonths(6));
//        docAssign.setStatus(AssignmentStatus.ACTIVE);
//
//        Role receptionRole = new Role();
//        receptionRole.setRoleId(3L);
//        receptionRole.setRoleName("LE_TAN");
//        receptionRole.setIsActive(true);
//
//        activeReceptionist = new StaffProfile();
//        activeReceptionist.setStaffId(201L);
//        activeReceptionist.setStaffCode("NV_LT01");
//        activeReceptionist.setFullName("Lê Thị Lễ Tân");
//        activeReceptionist.setPositionTitle("Lễ tân");
//        activeReceptionist.setStatus(StaffStatus.ACTIVE);
//
//        StaffBranchAssignment recAssign = new StaffBranchAssignment();
//        recAssign.setAssignmentId(2L);
//        recAssign.setStaff(activeReceptionist);
//        recAssign.setBranch(activeBranch);
//        recAssign.setRole(receptionRole);
//        recAssign.setStartDate(LocalDate.now().minusMonths(3));
//        recAssign.setStatus(AssignmentStatus.ACTIVE);
//
//        // Dữ liệu mẫu bài viết tin tức
//        publishedNews = new NewsArticle();
//        publishedNews.setNewsId(300L);
//        publishedNews.setTitle("Hướng dẫn chăm sóc răng sau cấy ghép Implant");
//        publishedNews.setSlug("huong-dan-cham-soc-rang-sau-cay-ghep-implant");
//        publishedNews.setSummary("Những lưu ý quan trọng giúp trụ Implant tích hợp xương nhanh chóng.");
//        publishedNews.setContent("<p>Nội dung hướng dẫn chi tiết.</p>");
//        publishedNews.setStatus("PUBLISHED");
//        publishedNews.setBranch(activeBranch);
//        publishedNews.setPublishedAt(LocalDateTime.now().minusDays(1));
//        publishedNews.setCreatedAt(LocalDateTime.now().minusDays(2));
//
//        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc())
//                .thenReturn(List.of(activeBranch));
//        when(categoryRepository.findByIsActiveTrueOrderByDisplayOrderAscCategoryNameAsc())
//                .thenReturn(List.of(activeCategory));
//        when(serviceRepository.findByStatusAndCategory_IsActiveTrueOrderByServiceNameAsc(ServiceStatus.ACTIVE))
//                .thenReturn(List.of(activeService));
//        when(serviceRepository.searchPublicServices(eq(ServiceStatus.ACTIVE), any(), any(), anyString()))
//                .thenReturn(List.of(activeService));
//        when(serviceRepository.findPublicServices(eq(ServiceStatus.ACTIVE), any(), any()))
//                .thenReturn(List.of(activeService));
//        when(serviceRepository.findById(100L))
//                .thenReturn(Optional.of(activeService));
//        when(staffProfileRepository.findByStatus(StaffStatus.ACTIVE))
//                .thenReturn(List.of(activeDoctor, activeReceptionist));
//        when(staffProfileRepository.findById(200L))
//                .thenReturn(Optional.of(activeDoctor));
//        when(staffProfileRepository.findById(201L))
//                .thenReturn(Optional.of(activeReceptionist));
//        when(assignmentRepository.findAllByStatus(AssignmentStatus.ACTIVE))
//                .thenReturn(List.of(docAssign, recAssign));
//        when(assignmentRepository.findByStaffId(200L))
//                .thenReturn(List.of(docAssign));
//        when(assignmentRepository.findByStaffId(201L))
//                .thenReturn(List.of(recAssign));
//        when(newsRepository.searchPublishedPublic(anyString(), any(), any(Pageable.class)))
//                .thenReturn(new PageImpl<>(List.of(publishedNews)));
//        when(newsRepository.findBySlug("huong-dan-cham-soc-rang-sau-cay-ghep-implant"))
//                .thenReturn(Optional.of(publishedNews));
//        when(commentRepository.findByNews_NewsIdAndParentCommentIsNullAndStatus(anyLong(), anyString(), any(Pageable.class)))
//                .thenReturn(new PageImpl<>(List.of()));
//    }
//
//    // =====================================================================
//    // 1. KIỂM THỬ GUEST TRUY CẬP CÁC TRANG CÔNG KHAI
//    // =====================================================================
//    @Test
//    void guestAccess_HomeAndLandingPages_RendersCustomerPortalHomeWithRealData() {
//        MockHttpSession guestSession = new MockHttpSession();
//        Model modelRoot = new ExtendedModelMap();
//        String viewRoot = userController.customerPortalLanding(guestSession, modelRoot);
//
//        assertEquals("portal/home", viewRoot);
//        assertEquals("home", modelRoot.getAttribute("activeNav"));
//        assertNotNull(modelRoot.getAttribute("featuredServices"));
//        assertNotNull(modelRoot.getAttribute("featuredDoctors"));
//        assertNotNull(modelRoot.getAttribute("latestNews"));
//        assertNotNull(modelRoot.getAttribute("branches"));
//
//        // Guest vào /home cũng được xem trang chủ Customer Portal mà không bị ép redirect /login
//        Model modelHome = new ExtendedModelMap();
//        String viewHome = userController.home(null, guestSession, modelHome);
//        assertEquals("portal/home", viewHome);
//    }
//
//    @Test
//    void guestAccess_PublicDoctorsFilterOnlyDoctorRole_ExcludesNonDoctorStaff() {
//        List<PublicDoctorCardDTO> doctors = customerPortalService.getPublicDoctors(null, null, null);
//        assertEquals(1, doctors.size(), "Chỉ nhân sự có vai trò BAC_SI đang ACTIVE mới được hiển thị ở trang Bác sĩ");
//        assertEquals("BS.CKI Phạm Minh Tuấn", doctors.get(0).getFullName());
//        assertEquals("012345/BYT-CCHN", doctors.get(0).getPracticeLicenseNo());
//        assertTrue(doctors.get(0).getBranchNames().contains("Cơ sở Cầu Giấy"));
//    }
//
//    @Test
//    void guestAccess_DoctorDetailWithSchedule_ReturnsDetailView() {
//        WorkShift morningShift = new WorkShift();
//        morningShift.setShiftId(1L);
//        morningShift.setShiftName("Ca sáng");
//        morningShift.setStartTime(LocalTime.of(8, 0));
//        morningShift.setEndTime(LocalTime.of(12, 0));
//
//        StaffSchedule schedule = new StaffSchedule();
//        schedule.setScheduleId(500L);
//        schedule.setStaff(activeDoctor);
//        schedule.setBranch(activeBranch);
//        schedule.setShift(morningShift);
//        schedule.setWorkDate(LocalDate.now().plusDays(1));
//        schedule.setStatus(ScheduleStatus.SCHEDULED);
//
//        when(scheduleRepository.findByStaffAndRange(eq(200L), any(LocalDate.class), any(LocalDate.class)))
//                .thenReturn(List.of(schedule));
//
//        MockHttpServletResponse response = new MockHttpServletResponse();
//        Model model = new ExtendedModelMap();
//        String view = customerPortalController.showPublicDoctorDetail(200L, response, model);
//
//        assertEquals("doctors/detail", view);
//        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
//        PublicDoctorCardDTO dto = (PublicDoctorCardDTO) model.getAttribute("doctor");
//        assertNotNull(dto);
//        assertEquals(1, dto.getUpcomingSchedules().size());
//    }
//
//    @Test
//    void guestAccess_NewsListAndDetail_AccessibleWithoutLogin() {
//        MockHttpSession guestSession = new MockHttpSession();
//        Model listModel = new ExtendedModelMap();
//
//        String listView = newsController.publicNewsList("", null, 0, 6, guestSession, listModel);
//        assertEquals("news/list", listView, "Guest phải xem được danh sách tin tức mà không bị redirect /login");
//
//        MockHttpServletResponse response = new MockHttpServletResponse();
//        Model detailModel = new ExtendedModelMap();
//        String detailView = newsController.newsDetail(
//                "huong-dan-cham-soc-rang-sau-cay-ghep-implant",
//                0,
//                guestSession,
//                response,
//                detailModel
//        );
//        assertEquals("news/detail", detailView);
//        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
//    }
//
//    // =====================================================================
//    // 2. KIỂM THỬ DỊCH VỤ & KHUYẾN MÃI HỢP LỆ TỪ DB
//    // =====================================================================
//    @Test
//    void servicePromotionCalculation_WithValidActivePromotion_CalculatesDiscountCorrectly() {
//        Promotion promo = new Promotion();
//        promo.setPromotionId(50L);
//        promo.setPromoCode("ZIRCONIA20");
//        promo.setPromoName("Ưu đãi 20% Răng sứ Zirconia");
//        promo.setPromoType(PromotionType.PERCENTAGE);
//        promo.setDiscountValue(new BigDecimal("20"));
//        promo.setApplicableService(activeService);
//        promo.setBranch(activeBranch);
//        promo.setStartDate(LocalDateTime.now().minusDays(2));
//        promo.setEndDate(LocalDateTime.now().plusDays(10));
//        promo.setIsActive(true);
//
//        when(promotionRepository.findActiveServicePromotions(any(LocalDateTime.class)))
//                .thenReturn(List.of(promo));
//
//        Map<Long, ServicePromotionViewDTO> promoMap = customerPortalService.getActiveServicePromotionsMap();
//        assertTrue(promoMap.containsKey(100L));
//        ServicePromotionViewDTO promoDto = promoMap.get(100L);
//        assertEquals(new BigDecimal("4000000"), promoDto.getOriginalPrice());
//        assertEquals(new BigDecimal("3200000"), promoDto.getDiscountedPrice());
//        assertEquals("-20%", promoDto.getDiscountBadge());
//    }
//
//    // =====================================================================
//    // 3. KIỂM THỬ XỬ LÝ 404 AN TOÀN CHO ID KHÔNG TỒN TẠI / DỮ LIỆU INACTIVE
//    // =====================================================================
//    @Test
//    void invalidOrInactiveEntities_ReturnNotFoundViewWith404Status() {
//        // 1. Dịch vụ không tồn tại
//        when(serviceRepository.findById(9999L)).thenReturn(Optional.empty());
//        MockHttpServletResponse resp1 = new MockHttpServletResponse();
//        Model model1 = new ExtendedModelMap();
//        String view1 = publicServiceController.showPublicServiceDetail(9999L, resp1, model1);
//        assertEquals("portal/not-found", view1);
//        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp1.getStatus());
//
//        // 2. Dịch vụ INACTIVE không được hiển thị công khai
//        Service inactiveService = new Service();
//        inactiveService.setServiceId(101L);
//        inactiveService.setCategory(activeCategory);
//        inactiveService.setStatus(ServiceStatus.INACTIVE);
//        when(serviceRepository.findById(101L)).thenReturn(Optional.of(inactiveService));
//        MockHttpServletResponse resp2 = new MockHttpServletResponse();
//        Model model2 = new ExtendedModelMap();
//        String view2 = publicServiceController.showPublicServiceDetail(101L, resp2, model2);
//        assertEquals("portal/not-found", view2);
//        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp2.getStatus());
//
//        // 3. Nhân sự không phải BAC_SI (ví dụ LE_TAN id=201L) không được hiển thị ở /doctors/{id}
//        MockHttpServletResponse resp3 = new MockHttpServletResponse();
//        Model model3 = new ExtendedModelMap();
//        String view3 = customerPortalController.showPublicDoctorDetail(201L, resp3, model3);
//        assertEquals("portal/not-found", view3);
//        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp3.getStatus());
//
//        // 4. Bài viết DRAFT không được hiển thị ở /news/{slug}
//        NewsArticle draftNews = new NewsArticle();
//        draftNews.setNewsId(301L);
//        draftNews.setSlug("bai-viet-nhap");
//        draftNews.setStatus("DRAFT");
//        when(newsRepository.findBySlug("bai-viet-nhap")).thenReturn(Optional.of(draftNews));
//        MockHttpServletResponse resp4 = new MockHttpServletResponse();
//        Model model4 = new ExtendedModelMap();
//        String view4 = newsController.newsDetail("bai-viet-nhap", 0, new MockHttpSession(), resp4, model4);
//        assertEquals("portal/not-found", view4);
//        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp4.getStatus());
//    }
//
//    // =====================================================================
//    // 4. KIỂM THỬ PHÂN QUYỀN GUEST vs BỆNH NHÂN vs NHÂN VIÊN
//    // =====================================================================
//    @Test
//    void roleSeparation_PatientVsStaffVsGuest_EnforcedCorrectly() {
//        MockHttpServletRequest request = new MockHttpServletRequest();
//
//        // Guest
//        MockHttpSession guestSession = new MockHttpSession();
//        assertFalse(globalAdvice.populateIsPatientUser(request, guestSession));
//        assertFalse(globalAdvice.populateIsStaffUser(request, guestSession));
//        assertNull(portalAccess.requirePatientUser(guestSession, new RedirectAttributesModelMap()));
//
//        // Bệnh nhân (BENH_NHAN)
//        Role patientRole = new Role();
//        patientRole.setRoleName("BENH_NHAN");
//        patientRole.setIsActive(true);
//        User patientUser = new User();
//        patientUser.setUserId(50L);
//        patientUser.setUsername("benhnhan01");
//        patientUser.setIsActive(true);
//        patientUser.setRoles(Set.of(patientRole));
//
//        PatientProfile patientProfile = new PatientProfile();
//        patientProfile.setProfileId(500L);
//        patientProfile.setFullName("Nguyễn Văn Bệnh Nhân");
//        patientProfile.setIsDefault(true);
//        patientProfile.setStatus("ACTIVE");
//        patientProfile.setPreferredBranch(activeBranch);
//
//        when(userRepository.findById(50L)).thenReturn(Optional.of(patientUser));
//        when(userRepository.findActiveRoleNamesByUserId(50L)).thenReturn(List.of("BENH_NHAN"));
//        when(patientProfileRepository.findFirstByUser_UserIdAndIsDefaultTrue(50L)).thenReturn(Optional.of(patientProfile));
//        when(patientProfileRepository.findFirstByUser_UserIdAndIsDefaultTrueAndStatus(50L, "ACTIVE")).thenReturn(Optional.of(patientProfile));
//
//        MockHttpSession patientSession = new MockHttpSession();
//        patientSession.setAttribute("loggedInUser", patientUser);
//        patientSession.setAttribute("roleNames", List.of("BENH_NHAN"));
//
//        assertTrue(globalAdvice.populateIsPatientUser(request, patientSession));
//        assertFalse(globalAdvice.populateIsStaffUser(request, patientSession));
//        assertNotNull(portalAccess.requirePatientUser(patientSession, new RedirectAttributesModelMap()));
//        assertEquals("portal/home", userController.home(null, patientSession, new ExtendedModelMap()));
//
//        // Nhân viên (BAC_SI / ADMIN)
//        Role adminRole = new Role();
//        adminRole.setRoleName("ADMIN");
//        adminRole.setIsActive(true);
//        User adminUser = new User();
//        adminUser.setUserId(1L);
//        adminUser.setUsername("admin");
//        adminUser.setIsActive(true);
//        adminUser.setRoles(Set.of(adminRole));
//
//        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
//        when(userRepository.findActiveRoleNamesByUserId(1L)).thenReturn(List.of("ADMIN"));
//        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
//
//        MockHttpSession staffSession = new MockHttpSession();
//        staffSession.setAttribute("loggedInUser", adminUser);
//        staffSession.setAttribute("roleNames", List.of("ADMIN"));
//
//        assertTrue(globalAdvice.populateIsStaffUser(request, staffSession));
//        assertFalse(globalAdvice.populateIsPatientUser(request, staffSession), "Không tự động coi nhân viên là bệnh nhân");
//        assertNull(portalAccess.requirePatientUser(staffSession, new RedirectAttributesModelMap()),
//                "Nhân viên không được truy cập các trang /portal/records hoặc /portal/appointments của bệnh nhân");
//        assertEquals("user/home", userController.home(null, staffSession, new ExtendedModelMap()),
//                "Nhân viên vào /home phải giữ nguyên giao diện bàn làm việc nội bộ user/home");
//        assertEquals("portal/home", userController.customerPortalLanding(staffSession, new ExtendedModelMap()),
//                "Nhân viên vào / vẫn xem được giao diện Customer Portal công khai");
//    }
//}
