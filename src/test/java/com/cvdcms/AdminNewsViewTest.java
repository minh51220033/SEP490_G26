package com.cvdcms;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AdminNewsViewTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    private MockHttpSession createAdminSession() {
        User admin = new User();
        admin.setUserId(1L);
        admin.setUsername("admin");
        Role adminRole = new Role();
        adminRole.setRoleId(1L);
        adminRole.setRoleName("ADMIN");
        admin.setRoles(Set.of(adminRole));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", admin);
        return session;
    }

    @Test
    void testRenderAdminNewsPage() throws Exception {
        mockMvc.perform(get("/admin/news").session(createAdminSession()))
                .andExpect(status().isOk());
    }

    @Test
    void testRenderAdminCommentsPage() throws Exception {
        mockMvc.perform(get("/admin/comments").session(createAdminSession()))
                .andExpect(status().isOk());
    }

    @Test
    void testRenderAdminBansPage() throws Exception {
        mockMvc.perform(get("/admin/bans").session(createAdminSession()))
                .andExpect(status().isOk());
    }
}
