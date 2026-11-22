package com.cvdcms.repository;

import com.cvdcms.entity.BranchRoomTransitionRule;
import com.cvdcms.entity.enums.TransitionRuleScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BranchRoomTransitionRuleRepository extends JpaRepository<BranchRoomTransitionRule, Long> {

    List<BranchRoomTransitionRule> findByBranchBranchIdAndIsActiveTrueOrderByRuleScopeAscRuleIdAsc(Long branchId);

    Optional<BranchRoomTransitionRule> findByBranchBranchIdAndRuleScopeAndFromRoomRoomIdAndToRoomRoomIdAndIsActiveTrue(
            Long branchId,
            TransitionRuleScope ruleScope,
            Long fromRoomId,
            Long toRoomId
    );

    boolean existsByRuleSignatureAndActiveGuard(String ruleSignature, Byte activeGuard);
}
