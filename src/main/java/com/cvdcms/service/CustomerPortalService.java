package com.cvdcms.service;

import com.cvdcms.DTO.PublicDoctorCardDTO;
import com.cvdcms.DTO.ServicePromotionViewDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.NewsArticle;
import com.cvdcms.entity.Service;

import java.util.List;
import java.util.Map;

public interface CustomerPortalService {

    List<Branch> getActiveBranches();

    Branch getPrimaryBranch();

    List<Service> getFeaturedServices(int limit);

    Map<Long, ServicePromotionViewDTO> getActiveServicePromotionsMap();

    ServicePromotionViewDTO getServicePromotion(Service service);

    List<Service> getRelatedPublicServices(Long excludeServiceId, Long categoryId, int limit);

    List<PublicDoctorCardDTO> getPublicDoctors(String keyword, String specialty, Long branchId);

    List<PublicDoctorCardDTO> getFeaturedDoctors(int limit);

    List<String> getPublicDoctorSpecialties();

    PublicDoctorCardDTO getPublicDoctorById(Long staffId);

    List<NewsArticle> getLatestPublishedNews(int limit);

    // Rút gọn mô tả HTML thành văn bản thuần để hiển thị thẻ dịch vụ.
    String plainTextSummary(String html, int maxLength);
}
