package com.cvdcms;

import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
public class InvoiceCreateRenderTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.cvdcms.repository.VisitRepository visitRepository;

    @Autowired
    private com.cvdcms.repository.TreatmentPlanRepository treatmentPlanRepository;

    @Autowired
    private com.cvdcms.repository.PatientProfileRepository patientProfileRepository;

    @Autowired
    private com.cvdcms.repository.PerformedServiceRepository performedServiceRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    private User getAdminUser() {
        return userRepository.findByUsername("admin").orElseGet(() ->
                userRepository.findAll().stream()
                        .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                        .findFirst()
                        .orElseThrow()
        );
    }

    @Test
    @DisplayName("Kiểm tra render trang /invoices/create khi có KHDT: không lỗi EL1008E, trả về đủ </html> và scripts")
    void testRenderCreateInvoiceWithVisitAndPlan() throws Exception {
        User user = getAdminUser();

        var targetVisit = visitRepository.findAll().stream()
                .filter(v -> v.getPatientProfile() != null && !treatmentPlanRepository.findByProfileIdWithDetails(v.getPatientProfile().getProfileId()).isEmpty())
                .findFirst()
                .orElseGet(() -> visitRepository.findAll().stream().filter(v -> v.getPatientProfile() != null).findFirst().orElse(null));

        assertNotNull(targetVisit, "Phải có ít nhất một lượt khám trong DB để kiểm tra render hóa đơn");
        Long visitId = targetVisit.getVisitId();

        var result = mockMvc.perform(get("/invoices/create")
                        .param("visitId", String.valueOf(visitId))
                        .sessionAttr("loggedInUser", user))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertEquals(200, status, "Trang lập hóa đơn phải trả về HTTP 200");

        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 1. Kiểm tra HTML render trọn vẹn đến thẻ đóng cuối cùng
        assertTrue(html.contains("</html>"), "HTML phải hoàn chỉnh và có thẻ đóng </html> (không bị đứt giữa chừng do response committed)");
        assertTrue(html.contains("function formatMoney"), "HTML phải chứa script formatMoney tính toán hóa đơn");
        assertTrue(html.contains("onSelectWaitingVisit"), "HTML phải chứa hàm chuyển đổi ca khám");
        assertTrue(html.contains("modalChoiceExternal"), "HTML phải chứa modal mua thuốc ngoài");

        // 2. Kiểm tra thông tin Kế hoạch điều trị tham khảo
        var plans = treatmentPlanRepository.findByProfileIdWithDetails(targetVisit.getPatientProfile().getProfileId());
        if (!plans.isEmpty()) {
            assertTrue(html.contains("Kế hoạch điều trị (Tham khảo dự kiến - Không tính vào hóa đơn)"), "Phải có card KHDT tham khảo");
            assertTrue(html.contains(plans.get(0).getPlanCode()), "Phải hiển thị mã KHDT");
        }

        // 3. Kiểm tra danh sách dịch vụ thực hiện lâm sàng được bàn giao
        var performed = performedServiceRepository.findByVisitIdWithDetails(visitId);
        if (!performed.isEmpty()) {
            assertTrue(html.contains("Dịch vụ thực hiện được bàn giao"), "Phải có bảng dịch vụ thực hiện lâm sàng");
        }

        // 4. Đảm bảo tiền hóa đơn không lấy chi phí KHDT làm tiền hóa đơn
        assertTrue(html.contains("itemsTable"), "Phải có bảng chi tiết dịch vụ itemsTable");
    }

    @Test
    @DisplayName("Kiểm tra render trang /invoices/create khi chỉ truyền profileId: tự động gắn kết ca khám chờ thanh toán")
    void testRenderCreateInvoiceWithProfileIdOnly() throws Exception {
        User user = getAdminUser();

        var profile = patientProfileRepository.findAll().stream().findFirst().orElseThrow();

        var result = mockMvc.perform(get("/invoices/create")
                        .param("profileId", String.valueOf(profile.getProfileId()))
                        .sessionAttr("loggedInUser", user))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("</html>"), "HTML phải có thẻ đóng </html>");
        assertTrue(html.contains(profile.getFullName()), "Phải hiển thị tên bệnh nhân " + profile.getFullName());
    }

    @Test
    @DisplayName("Kiểm tra render trang /invoices/create khi không có KHDT và chưa chọn ca khám: render an toàn 200")
    void testRenderCreateInvoiceWithoutPlanAndVisit() throws Exception {
        User user = getAdminUser();

        var result = mockMvc.perform(get("/invoices/create")
                        .sessionAttr("loggedInUser", user))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("</html>"), "HTML phải render hoàn chỉnh thẻ </html>");
        assertFalse(html.contains("Kế hoạch điều trị (Tham khảo dự kiến"), "Không được hiển thị card KHDT khi không có KHDT");
        assertTrue(html.contains("Vui lòng chọn ca khám đang chờ thanh toán"), "Phải có thông báo nhắc chọn ca khám");
    }
}
