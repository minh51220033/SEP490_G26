package com.cvdcms.repository;

import com.cvdcms.entity.MembershipTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipTierRepository extends JpaRepository<MembershipTier, Long> {

    Optional<MembershipTier> findByTierCode(String tierCode);

    List<MembershipTier> findByIsActiveTrueOrderByMinTotalSpentAsc();

    List<MembershipTier> findAllByOrderByDisplayOrderAsc();

    List<MembershipTier> findByIsActiveTrueOrderByDisplayOrderAsc();

    boolean existsByTierCode(String tierCode);

    boolean existsByTierCodeAndTierIdNot(String tierCode, Long tierId);
}
