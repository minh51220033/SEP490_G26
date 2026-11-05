package com.cvdcms;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.TreatmentPlan;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.TreatmentPlanStatus;
import com.cvdcms.patient.ReceptionPatientRecord;
import com.cvdcms.patient.ReceptionPatientRepository;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.TreatmentPlanRepository;
import com.cvdcms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
public class AdminPatientDetailTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReceptionPatientRepository receptionPatientRepository;

    @Autowired
    private PatientProfileRepository patientProfileRepository;

    @Autowired
    private TreatmentPlanRepository treatmentPlanRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private StaffProfileRepository staffProfileRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    private User getAdminUser() {
        User adminUser = userRepository.findByUsername("admin").orElse(null);
        if (adminUser == null) {
            adminUser = userRepository.findAll().stream()
                    .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                    .findFirst().orElseThrow();
        }
        return adminUser;
    }

    @Test
    @DisplayName("Kiểm tra mở chi tiết toàn bộ hồ sơ bệnh nhân trong hệ thống")
    void testOpenAllPatientDetails() {
        User adminUser = getAdminUser();
        List<ReceptionPatientRecord> patients = receptionPatientRepository.findAll();
        System.out.println("=== TỔNG SỐ BỆNH NHÂN TRONG DB: " + patients.size() + " ===");

        for (ReceptionPatientRecord p : patients) {
            Long profileId = p.getProfileId();
            try {
                var result = mockMvc.perform(get("/admin/patients/" + profileId)
                        .sessionAttr("loggedInUser", adminUser))
                        .andReturn();
                int status = result.getResponse().getStatus();
                System.out.println("Patient profileId=" + profileId + " (" + p.getFullName() + ", code=" + p.getPatientCode() + ", userId=" + p.getUserId() + ") -> HTTP " + status);
                org.junit.jupiter.api.Assertions.assertEquals(200, status, "Bệnh nhân profileId=" + profileId + " phải trả về 200");
                String html = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                org.junit.jupiter.api.Assertions.assertTrue(html.contains("</html>"), "HTML phải hoàn chỉnh có thẻ đóng </html> cho profileId=" + profileId);
                org.junit.jupiter.api.Assertions.assertTrue(html.contains("switchTab"), "HTML phải có script switchTab cho profileId=" + profileId);
            } catch (Exception e) {
                System.out.println("EXCEPTION for profileId=" + profileId + ": " + e.getMessage());
                e.printStackTrace(System.out);
                org.junit.jupiter.api.Assertions.fail("Exception xảy ra cho profileId=" + profileId + ": " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("Kiểm tra chi tiết hồ sơ 15 (có kế hoạch điều trị) và hồ sơ 1 (không có kế hoạch) đầy đủ tab và script")
    void testPatientDetailWithAndWithoutTreatmentPlan() throws Exception {
        User adminUser = getAdminUser();
        String[] expectedTabs = {"personal", "clinical", "appointments", "care", "prescriptions", "labo", "photos", "instructions", "invoices", "audit"};

        // 1. Kiểm tra hồ sơ 15 (có kế hoạch điều trị)
        var result15 = mockMvc.perform(get("/admin/patients/15")
                .sessionAttr("loggedInUser", adminUser))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, result15.getResponse().getStatus());
        String html15 = result15.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // Kiểm tra kết thúc HTML hoàn chỉnh và script chuyển tab
        org.junit.jupiter.api.Assertions.assertTrue(html15.contains("</html>"), "Hồ sơ 15 phải render hoàn chỉnh thẻ </html>");
        org.junit.jupiter.api.Assertions.assertTrue(html15.contains("function switchTab(tabId)"), "Hồ sơ 15 phải có script switchTab");
        org.junit.jupiter.api.Assertions.assertTrue(html15.contains("bootstrap.bundle.min.js"), "Hồ sơ 15 phải có bootstrap bundle script");

        // Kiểm tra khối kế hoạch điều trị được giữ nguyên dữ liệu
        org.junit.jupiter.api.Assertions.assertTrue(html15.contains("Kế hoạch / Lộ trình dài hạn:"), "Phải có tiêu đề Kế hoạch / Lộ trình dài hạn");
        org.junit.jupiter.api.Assertions.assertTrue(html15.contains("kế hoạch"), "Phải hiển thị số lượng kế hoạch");

        // In ra thông tin các kế hoạch điều trị của bệnh nhân 15
        List<TreatmentPlan> plans15 = treatmentPlanRepository.findByProfileIdWithDetails(15L);
        System.out.println("Số lượng kế hoạch điều trị của bệnh nhân 15: " + plans15.size());
        for (TreatmentPlan plan : plans15) {
            System.out.println("Plan ID=" + plan.getPlanId() + ", Name=" + plan.getPlanName() + ", Status=" + plan.getStatus() + ", Notes=" + plan.getNotes());
            org.junit.jupiter.api.Assertions.assertTrue(html15.contains(plan.getPlanName()), "HTML phải chứa tên kế hoạch: " + plan.getPlanName());
            if (plan.getNotes() != null && !plan.getNotes().isEmpty()) {
                org.junit.jupiter.api.Assertions.assertTrue(html15.contains(plan.getNotes()), "HTML phải hiển thị notes của kế hoạch");
            } else {
                org.junit.jupiter.api.Assertions.assertTrue(html15.contains("Không có mô tả chi tiết"), "HTML phải hiển thị fallback khi notes null");
            }
        }

        // Kiểm tra tất cả các nút tab và pane đều tồn tại trong DOM
        for (String tabId : expectedTabs) {
            org.junit.jupiter.api.Assertions.assertTrue(html15.contains("id=\"tab-" + tabId + "-btn\""), "Phải có nút tab: tab-" + tabId + "-btn");
            org.junit.jupiter.api.Assertions.assertTrue(html15.contains("id=\"" + tabId + "\""), "Phải có tab-pane: " + tabId);
        }

        // 2. Kiểm tra hồ sơ không có kế hoạch điều trị (profileId 1)
        var result1 = mockMvc.perform(get("/admin/patients/1")
                .sessionAttr("loggedInUser", adminUser))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, result1.getResponse().getStatus());
        String html1 = result1.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(html1.contains("Chưa lập kế hoạch điều trị dài hạn."), "Hồ sơ 1 phải thông báo chưa lập kế hoạch");
        org.junit.jupiter.api.Assertions.assertTrue(html1.contains("</html>"), "Hồ sơ 1 phải render hoàn chỉnh thẻ </html>");
        org.junit.jupiter.api.Assertions.assertTrue(html1.contains("function switchTab(tabId)"), "Hồ sơ 1 phải có script switchTab");

        for (String tabId : expectedTabs) {
            org.junit.jupiter.api.Assertions.assertTrue(html1.contains("id=\"tab-" + tabId + "-btn\""), "Phải có nút tab: tab-" + tabId + "-btn");
            org.junit.jupiter.api.Assertions.assertTrue(html1.contains("id=\"" + tabId + "\""), "Phải có tab-pane: " + tabId);
        }
    }

    @Test
    @Transactional
    @DisplayName("Kiểm tra kế hoạch điều trị với các trường tùy chọn null (notes=null, startDate=null, expectedEndDate=null)")
    void testTreatmentPlanWithNullOptionalFields() throws Exception {
        User adminUser = getAdminUser();
        PatientProfile patient = patientProfileRepository.findById(1L).orElseThrow();
        Branch branch = branchRepository.findAll().stream().findFirst().orElseThrow();
        StaffProfile doctor = staffProfileRepository.findAll().stream().findFirst().orElseThrow();

        TreatmentPlan plan = new TreatmentPlan();
        plan.setPlanCode("P" + System.currentTimeMillis());
        plan.setPatientProfile(patient);
        plan.setBranch(branch);
        plan.setDoctor(doctor);
        plan.setPlanName("Kế hoạch kiểm thử trường null");
        plan.setTotalEstimatedCost(BigDecimal.valueOf(1000000));
        plan.setFinalEstimatedCost(BigDecimal.valueOf(1000000));
        plan.setStatus(TreatmentPlanStatus.IN_PROGRESS);
        plan.setStartDate(null);
        plan.setExpectedEndDate(null);
        plan.setNotes(null); // Trường notes null để kiểm tra fallback
        treatmentPlanRepository.save(plan);

        var result = mockMvc.perform(get("/admin/patients/1")
                .sessionAttr("loggedInUser", adminUser))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, result.getResponse().getStatus());
        String html = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // Phải chứa fallback "Không có mô tả chi tiết" do notes là null
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Kế hoạch kiểm thử trường null"), "Phải render tên kế hoạch");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Không có mô tả chi tiết"), "Phải render mô tả fallback khi notes=null");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("</html>"), "HTML phải hoàn chỉnh có thẻ đóng </html>");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("function switchTab(tabId)"), "Script switchTab phải tồn tại");
    }

    @Test
    @DisplayName("Kiểm tra mở danh sách khách hàng Admin, phân trang 10/trang, bộ đếm DentalSoft và không còn 3 thẻ KPI")
    void testOpenPatientList() throws Exception {
        User adminUser = getAdminUser();

        // 1. Kiểm tra trang 0 (Page 1)
        var resultPage0 = mockMvc.perform(get("/admin/patients?page=0")
                .sessionAttr("loggedInUser", adminUser))
                .andReturn();
        int statusPage0 = resultPage0.getResponse().getStatus();
        String contentPage0 = resultPage0.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("GET /admin/patients?page=0 -> HTTP " + statusPage0);
        org.junit.jupiter.api.Assertions.assertEquals(200, statusPage0);

        // Kiểm tra không còn 3 thẻ KPI trên danh sách
        org.junit.jupiter.api.Assertions.assertFalse(contentPage0.contains("Tổng số khách hàng"), "Không được chứa thẻ Tổng số khách hàng");
        org.junit.jupiter.api.Assertions.assertFalse(contentPage0.contains("Trạng thái hồ sơ"), "Không được chứa thẻ Trạng thái hồ sơ");
        org.junit.jupiter.api.Assertions.assertFalse(contentPage0.contains("Theo dõi công nợ"), "Không được chứa thẻ Theo dõi công nợ");

        // Kiểm tra nút Thêm bệnh nhân có nhãn mới và không còn nút cũ ở header
        org.junit.jupiter.api.Assertions.assertTrue(contentPage0.contains("Thêm bệnh nhân"), "Phải có nhãn Thêm bệnh nhân");
        org.junit.jupiter.api.Assertions.assertFalse(contentPage0.contains("Thêm bệnh nhân mới"), "Không được còn nhãn Thêm bệnh nhân mới");

        // Đọc tổng số thực tế từ DB để kiểm tra bộ đếm động theo đúng nguyên tắc không hardcode
        long totalPatients = receptionPatientRepository.findAll().size();
        int expectedPage0 = (int) Math.min(10, totalPatients);
        int expectedPage1 = (int) Math.max(0, totalPatients - 10);

        // Kiểm tra chỉ báo hiển thị trang 0
        org.junit.jupiter.api.Assertions.assertTrue(contentPage0.contains("Hiển thị <b"), "Phải chứa cấu trúc đếm Hiển thị");
        org.junit.jupiter.api.Assertions.assertTrue(
                contentPage0.contains(expectedPage0 + "</b>/<span>" + totalPatients + "</span>"),
                "Trang 0 phải hiển thị " + expectedPage0 + "/" + totalPatients);

        // 2. Kiểm tra trang 1 (Page 2)
        var resultPage1 = mockMvc.perform(get("/admin/patients?page=1")
                .sessionAttr("loggedInUser", adminUser))
                .andReturn();
        int statusPage1 = resultPage1.getResponse().getStatus();
        String contentPage1 = resultPage1.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("GET /admin/patients?page=1 -> HTTP " + statusPage1);
        org.junit.jupiter.api.Assertions.assertEquals(200, statusPage1);

        // Kiểm tra chỉ báo hiển thị trang 1
        org.junit.jupiter.api.Assertions.assertTrue(
                contentPage1.contains(expectedPage1 + "</b>/<span>" + totalPatients + "</span>"),
                "Trang 1 phải hiển thị " + expectedPage1 + "/" + totalPatients);
    }
}
