package com.cvdcms.repository;

import com.cvdcms.entity.DentalChair;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DispatchChairRepository extends JpaRepository<DentalChair, Long> {
    List<DentalChair> findByBranchBranchIdOrderByChairCode(Long branch);
}