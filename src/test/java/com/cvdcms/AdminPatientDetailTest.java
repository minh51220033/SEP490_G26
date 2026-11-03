package com.cvdcms;

import com.cvdcms.entity.User;
import com.cvdcms.patient.ReceptionPatientRecord;
import com.cvdcms.patient.ReceptionPatientRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("Kiểm tra mở chi tiết toàn bộ hồ sơ bệnh nhân trong hệ thống")
    void testOpenAllPatientDetails() {
        User adminUser = userRepository.findByUsername("admin").orElse(null);
        if (adminUser == null) {
            adminUser = userRepository.findAll().stream()
                    .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                    .findFirst().orElseThrow();
        }

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
                if (status != 200) {
                    System.out.println("ERROR RESPONSE: " + result.getResponse().getErrorMessage());
                    if (result.getResolvedException() != null) {
                        result.getResolvedException().printStackTrace(System.out);
                    }
                }
            } catch (Exception e) {
                System.out.println("EXCEPTION for profileId=" + profileId + ": " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }
    }

    @Test
    @DisplayName("Kiểm tra mở danh sách khách hàng Admin, phân trang 10/trang, bộ đếm DentalSoft và không còn 3 thẻ KPI")
    void testOpenPatientList() throws Exception {
        User adminUser = userRepository.findByUsername("admin").orElse(null);
        if (adminUser == null) {
            adminUser = userRepository.findAll().stream()
                    .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                    .findFirst().orElseThrow();
        }

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

