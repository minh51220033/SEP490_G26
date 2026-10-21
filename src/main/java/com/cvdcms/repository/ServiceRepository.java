package com.cvdcms.repository;

import com.cvdcms.entity.Service;
import com.cvdcms.entity.enums.ServiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceRepository extends JpaRepository<Service, Long> {

    List<Service> findByStatusOrderByCategory_DisplayOrderAscServiceNameAsc(ServiceStatus status);

    @Query("SELECT s FROM Service s JOIN FETCH s.category WHERE s.status = :status ORDER BY s.category.displayOrder ASC, s.serviceName ASC")
    List<Service> findAllActiveWithCategory(ServiceStatus status);

    Optional<Service> findByServiceCode(String serviceCode);
}
