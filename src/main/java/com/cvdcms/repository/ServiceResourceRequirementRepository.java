package com.cvdcms.repository;

import com.cvdcms.entity.ServiceResourceRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceResourceRequirementRepository extends JpaRepository<ServiceResourceRequirement, Long> {

    List<ServiceResourceRequirement> findByServiceServiceIdOrderByRequirementIdAsc(Long serviceId);

    void deleteByServiceServiceId(Long serviceId);
}
