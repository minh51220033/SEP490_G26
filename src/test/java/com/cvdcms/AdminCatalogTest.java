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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
public class AdminCatalogTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private User adminUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        adminUser = userRepository.findByUsername("admin").orElse(null);
    }

    @Test
    @DisplayName("GET /admin/catalogs redirects to /admin/services")
    void testCatalogHubRedirects() throws Exception {
        mockMvc.perform(get("/admin/catalogs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/services"));
    }

    @Test
    @DisplayName("GET /admin/catalogs?tab=categories redirects to /admin/services/categories")
    void testCatalogHubCategoriesRedirect() throws Exception {
        mockMvc.perform(get("/admin/catalogs?tab=categories"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/services/categories"));
    }

    @Test
    @DisplayName("GET /admin/services renders tab 1 (Danh mục dịch vụ)")
    void testServicesTab() throws Exception {
        if (adminUser == null) return;
        mockMvc.perform(get("/admin/services")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("roleNames", List.of("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/services/list"))
                .andExpect(model().attribute("activeTab", "services"))
                .andExpect(content().string(containsString("Danh mục dịch vụ")))
                .andExpect(content().string(containsString("Nhóm dịch vụ")))
                .andExpect(content().string(containsString("Danh mục thuốc")))
                .andExpect(content().string(containsString("Danh mục ICD-10")));
    }

    @Test
    @DisplayName("GET /admin/services/categories renders tab 2 (Nhóm dịch vụ)")
    void testCategoriesTab() throws Exception {
        if (adminUser == null) return;
        mockMvc.perform(get("/admin/services/categories")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("roleNames", List.of("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/services/category-list"))
                .andExpect(model().attribute("activeTab", "categories"))
                .andExpect(content().string(containsString("Nhóm dịch vụ")));
    }

    @Test
    @DisplayName("GET /admin/medications renders tab 3 (Danh mục thuốc)")
    void testMedicationsTab() throws Exception {
        if (adminUser == null) return;
        mockMvc.perform(get("/admin/medications")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("roleNames", List.of("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/medications/list"))
                .andExpect(model().attribute("activeTab", "medications"))
                .andExpect(content().string(containsString("Danh mục thuốc nha khoa")));
    }

    @Test
    @DisplayName("GET /admin/icd10 renders tab 4 (Danh mục ICD-10)")
    void testIcd10Tab() throws Exception {
        if (adminUser == null) return;
        mockMvc.perform(get("/admin/icd10")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("roleNames", List.of("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/icd10/list"))
                .andExpect(model().attribute("activeTab", "icd10"))
                .andExpect(content().string(containsString("Danh mục mã bệnh ICD-10")));
    }

    @Test
    @DisplayName("GET /admin/services with search query and status filter")
    void testServicesSearchAndFilter() throws Exception {
        if (adminUser == null) return;
        mockMvc.perform(get("/admin/services")
                        .param("q", "cạo vôi")
                        .param("status", "ACTIVE")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("roleNames", List.of("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/services/list"))
                .andExpect(model().attribute("keyword", "cạo vôi"))
                .andExpect(model().attributeExists("services"));
    }

    @Test
    @DisplayName("GET /admin/services/categories with search query and active filter")
    void testCategoriesSearchAndFilter() throws Exception {
        if (adminUser == null) return;
        mockMvc.perform(get("/admin/services/categories")
                        .param("q", "thủ thuật")
                        .param("active", "true")
                        .sessionAttr("loggedInUser", adminUser)
                        .sessionAttr("roleNames", List.of("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/services/category-list"))
                .andExpect(model().attribute("keyword", "thủ thuật"))
                .andExpect(model().attribute("selectedActive", true))
                .andExpect(model().attributeExists("categories"));
    }
}

