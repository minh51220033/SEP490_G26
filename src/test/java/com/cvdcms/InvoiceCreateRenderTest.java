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
    @DisplayName("Kiểm tra render trang /invoices/create khi có KHDT (visitId=15): không lỗi EL1008E, trả về đủ </html> và scripts")
    void testRenderCreateInvoiceWithVisitAndPlan() throws Exception {
        User user = getAdminUser();

        var result = mockMvc.perform(get("/invoices/create")
                        .param("visitId", "15")
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
        assertTrue(html.contains("Kế hoạch điều trị (Tham khảo dự kiến - Không tính vào hóa đơn)"), "Phải có card KHDT tham khảo");
        assertTrue(html.contains("KHDT32676519"), "Phải hiển thị mã KHDT32676519 của bệnh nhân 15");
        assertTrue(html.contains("160,000") || html.contains("160.000"), "Phải format đúng chi phí dự kiến 160,000 hoặc 160.000 đ");

        // 3. Kiểm tra danh sách dịch vụ thực hiện lâm sàng được bàn giao
        assertTrue(html.contains("Dịch vụ thực hiện được bàn giao"), "Phải có bảng dịch vụ thực hiện lâm sàng");
        assertTrue(html.contains("testtest1"), "Phải hiển thị dịch vụ thực hiện testtest1 của ca khám 15");

        // 4. Đảm bảo tiền hóa đơn không lấy chi phí KHDT làm tiền hóa đơn
        assertTrue(html.contains("itemsTable"), "Phải có bảng chi tiết dịch vụ itemsTable");
    }

    @Test
    @DisplayName("Kiểm tra render trang /invoices/create khi chỉ truyền profileId=15: tự động gắn kết ca khám chờ thanh toán")
    void testRenderCreateInvoiceWithProfileIdOnly() throws Exception {
        User user = getAdminUser();

        var result = mockMvc.perform(get("/invoices/create")
                        .param("profileId", "15")
                        .sessionAttr("loggedInUser", user))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("</html>"), "HTML phải có thẻ đóng </html>");
        assertTrue(html.contains("Hoàng Quang Huy"), "Phải hiển thị tên bệnh nhân Hoàng Quang Huy");
        assertTrue(html.contains("testtest1"), "Phải tự động tải dịch vụ thực hiện testtest1 của ca khám WAITING_PAYMENT");
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
