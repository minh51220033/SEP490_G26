package com.cvdcms;

import com.cvdcms.config.UploadWebConfig;
import com.cvdcms.controller.GlobalModelAttributesAdvice;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Service;
import com.cvdcms.service.CustomerPortalService;
import com.cvdcms.service.impl.CustomerPortalServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomerPortalServiceBeanTest {

    @Test
    @DisplayName("1. UploadWebConfig không được khai báo bất kỳ @Bean nào trả về CustomerPortalService để tránh trùng lặp Bean")
    void testUploadWebConfigDoesNotDeclareCustomerPortalServiceBean() {
        Method[] methods = UploadWebConfig.class.getDeclaredMethods();
        boolean hasDuplicateBean = Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .anyMatch(m -> CustomerPortalService.class.isAssignableFrom(m.getReturnType()));

        assertFalse(hasDuplicateBean, "UploadWebConfig không được chứa phương thức @Bean trả về CustomerPortalService");
    }

    @Test
    @DisplayName("2. CustomerPortalServiceImpl là Implementation chính thức mang annotation @Service duy nhất")
    void testCustomerPortalServiceImplIsAnnotatedWithService() {
        assertTrue(
                CustomerPortalServiceImpl.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
                "CustomerPortalServiceImpl phải được đánh dấu @Service để Spring component scan tự động quản lý");
        assertTrue(
                CustomerPortalService.class.isAssignableFrom(CustomerPortalServiceImpl.class),
                "CustomerPortalServiceImpl phải implement CustomerPortalService");
    }

    @Test
    @DisplayName("3. GlobalModelAttributesAdvice thực thi an toàn và trả về danh sách hợp lệ khi có CustomerPortalService")
    void testGlobalModelAttributesAdviceMethods() {
        CustomerPortalService stubService = new DummyCustomerPortalService();

        ObjectProvider<CustomerPortalService> provider = new SimpleObjectProvider<>(stubService);
        GlobalModelAttributesAdvice advice = new GlobalModelAttributesAdvice(null, provider, null, null, null);

        assertDoesNotThrow(() -> {
            List<Service> services = advice.populateFooterServices();
            assertNotNull(services);
            assertEquals(1, services.size());
            assertEquals("Dịch vụ test", services.get(0).getServiceName());
        });

        assertDoesNotThrow(() -> {
            List<Branch> branches = advice.populatePublicBranches();
            assertNotNull(branches);
            assertEquals(1, branches.size());
            assertEquals("Cơ sở test", branches.get(0).getBranchName());
        });

        assertDoesNotThrow(() -> {
            Branch primaryBranch = advice.populatePrimaryBranch(null);
            assertNotNull(primaryBranch);
            assertEquals("Cơ sở test", primaryBranch.getBranchName());
        });
    }

    @Test
    @DisplayName("4. GlobalModelAttributesAdvice an toàn fail-safe ngay cả khi CustomerPortalService chưa sẵn sàng (null)")
    void testGlobalModelAttributesAdviceNullSafety() {
        ObjectProvider<CustomerPortalService> provider = new SimpleObjectProvider<>(null);
        GlobalModelAttributesAdvice advice = new GlobalModelAttributesAdvice(null, provider, null, null, null);

        assertDoesNotThrow(() -> {
            List<Service> services = advice.populateFooterServices();
            assertNotNull(services);
            assertTrue(services.isEmpty());
        });

        assertDoesNotThrow(() -> {
            List<Branch> branches = advice.populatePublicBranches();
            assertNotNull(branches);
            assertTrue(branches.isEmpty());
        });

        assertDoesNotThrow(() -> {
            Branch primaryBranch = advice.populatePrimaryBranch(null);
            assertNull(primaryBranch);
        });
    }

    private static class SimpleObjectProvider<T> implements ObjectProvider<T> {
        private final T instance;

        SimpleObjectProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject(Object... args) throws BeansException {
            return instance;
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return instance;
        }

        @Override
        public T getIfUnique() throws BeansException {
            return instance;
        }

        @Override
        public T getObject() throws BeansException {
            return instance;
        }
    }

    private static class DummyCustomerPortalService implements CustomerPortalService {
        @Override
        public List<Branch> getActiveBranches() {
            Branch b = new Branch();
            b.setBranchName("Cơ sở test");
            return List.of(b);
        }

        @Override
        public Branch getPrimaryBranch() {
            Branch b = new Branch();
            b.setBranchName("Cơ sở test");
            return b;
        }

        @Override
        public List<Service> getFeaturedServices(int limit) {
            Service s = new Service();
            s.setServiceName("Dịch vụ test");
            return List.of(s);
        }

        @Override
        public Map<Long, com.cvdcms.DTO.ServicePromotionViewDTO> getActiveServicePromotionsMap() {
            return Collections.emptyMap();
        }

        @Override
        public Map<Long, com.cvdcms.DTO.ServicePromotionViewDTO> getActiveServicePromotionsMap(Long branchId) {
            return Collections.emptyMap();
        }

        @Override
        public com.cvdcms.DTO.ServicePromotionViewDTO getServicePromotion(Service service) {
            return null;
        }

        @Override
        public List<Service> getRelatedPublicServices(Long excludeServiceId, Long categoryId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<com.cvdcms.DTO.PublicDoctorCardDTO> getPublicDoctors(String keyword, String specialty,
                Long branchId) {
            return Collections.emptyList();
        }

        @Override
        public List<com.cvdcms.DTO.PublicDoctorCardDTO> getFeaturedDoctors(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<com.cvdcms.DTO.PublicDoctorCardDTO> getFeaturedDoctors(int limit, Long branchId) {
            return Collections.emptyList();
        }

        @Override
        public List<String> getPublicDoctorSpecialties() {
            return Collections.emptyList();
        }

        @Override
        public com.cvdcms.DTO.PublicDoctorCardDTO getPublicDoctorById(Long staffId) {
            return null;
        }

        @Override
        public List<com.cvdcms.entity.NewsArticle> getLatestPublishedNews(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<com.cvdcms.entity.NewsArticle> getLatestPublishedNews(int limit, Long branchId) {
            return Collections.emptyList();
        }

        @Override
        public String plainTextSummary(String html, int maxLength) {
            return html;
        }
    }
}
