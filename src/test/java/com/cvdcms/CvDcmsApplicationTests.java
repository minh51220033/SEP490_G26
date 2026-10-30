package com.cvdcms;

import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.repository.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
class CvDcmsApplicationTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VisitRepository visitRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void contextLoads() {
    }

    @Test
    @DisplayName("GET /invoices/create renders finance/invoices/create without Thymeleaf TemplateProcessingException")
    void testGetCreateInvoicePage() throws Exception {
        User adminUser = userRepository.findByUsername("admin").orElseGet(() -> {
            User u = new User();
            u.setUserId(1L);
            u.setUsername("admin");
            return u;
        });

        // Test GET /invoices/create không có visitId
        mockMvc.perform(get("/invoices/create")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("displayName", "Admin User"))
                .andExpect(status().isOk())
                .andExpect(view().name("finance/invoices/create"));

        // Test GET /invoices/create có visitId nếu có lượt khám trong DB
        var anyVisit = visitRepository.findAll().stream().findFirst();
        if (anyVisit.isPresent()) {
            Long visitId = anyVisit.get().getVisitId();
            mockMvc.perform(get("/invoices/create")
                            .param("visitId", visitId.toString())
                            .sessionAttr("loggedInUser", adminUser)
                            .sessionAttr("displayName", "Admin User"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("finance/invoices/create"));
        }
    }

    @Test
    @DisplayName("GET /accounts/roles renders account/roles without Thymeleaf error")
    void testGetRolesPage() throws Exception {
        User adminUser = userRepository.findByUsername("admin").orElseGet(() -> {
            User u = new User();
            u.setUserId(1L);
            u.setUsername("admin");
            return u;
        });

        mockMvc.perform(get("/accounts/roles")
                        .param("editRoleId", "3")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("displayName", "Admin User"))
                .andExpect(status().isOk())
                .andExpect(view().name("account/roles"));

        mockMvc.perform(get("/accounts/roles")
                        .param("editRoleId", "6")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("displayName", "Admin User"))
                .andExpect(status().isOk())
                .andExpect(view().name("account/roles"));
    }

    @Test
    @DisplayName("GET /admin/staffs renders staff/list without Thymeleaf error")
    void testGetStaffsPage() throws Exception {
        User adminUser = userRepository.findByUsername("admin").orElseGet(() -> {
            User u = new User();
            u.setUserId(1L);
            u.setUsername("admin");
            return u;
        });

        mockMvc.perform(get("/admin/staffs")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("displayName", "Admin User"))
                .andExpect(status().isOk())
                .andExpect(view().name("staff/list"));
    }
}
