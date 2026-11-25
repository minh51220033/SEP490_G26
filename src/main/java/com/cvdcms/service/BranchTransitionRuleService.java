package com.cvdcms.service;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.BranchRoomTransitionRule;
import com.cvdcms.entity.Room;
import com.cvdcms.entity.enums.TransitionRuleScope;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.BranchRoomTransitionRuleRepository;
import com.cvdcms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class BranchTransitionRuleService {

    private final BranchRoomTransitionRuleRepository ruleRepository;
    private final BranchRepository branchRepository;
    private final RoomRepository roomRepository;

    @Transactional(readOnly = true)
    public List<BranchRoomTransitionRule> getActiveRules(Long branchId) {
        return ruleRepository.findByBranchBranchIdAndIsActiveTrueOrderByRuleScopeAscRuleIdAsc(branchId);
    }

    @Transactional
    public BranchRoomTransitionRule createRule(
            Long branchId,
            TransitionRuleScope scope,
            Long fromRoomId,
            Long toRoomId,
            String fromFloorLabel,
            String toFloorLabel,
            Integer travelBufferMinutes
    ) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh."));
        if (scope == null) {
            throw new IllegalArgumentException("Vui lòng chọn phạm vi quy tắc di chuyển.");
        }
        int buffer = travelBufferMinutes != null ? travelBufferMinutes : 0;
        if (buffer < 0 || buffer > 120) {
            throw new IllegalArgumentException("Thời gian di chuyển phải từ 0 đến 120 phút.");
        }

        BranchRoomTransitionRule rule = new BranchRoomTransitionRule();
        rule.setBranch(branch);
        rule.setRuleScope(scope);
        rule.setTravelBufferMinutes(buffer);
        rule.setIsActive(true);

        if (scope == TransitionRuleScope.SPECIFIC_PAIR) {
            if (fromRoomId == null || toRoomId == null || Objects.equals(fromRoomId, toRoomId)) {
                throw new IllegalArgumentException("Cặp phòng di chuyển (SPECIFIC_PAIR) phải chọn hai phòng khác nhau.");
            }
            Room fromRoom = roomRepository.findById(fromRoomId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng xuất phát."));
            Room toRoom = roomRepository.findById(toRoomId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phòng đích."));
            if (!Objects.equals(fromRoom.getBranch().getBranchId(), branchId)
                    || !Objects.equals(toRoom.getBranch().getBranchId(), branchId)) {
                throw new IllegalArgumentException("Cả hai phòng phải thuộc cùng chi nhánh.");
            }
            rule.setFromRoom(fromRoom);
            rule.setToRoom(toRoom);
        } else {
            if (fromRoomId != null || toRoomId != null) {
                throw new IllegalArgumentException("Quy tắc chung theo tầng không được gắn phòng cụ thể.");
            }
            rule.setFromFloorLabel(trimToNull(fromFloorLabel));
            rule.setToFloorLabel(trimToNull(toFloorLabel));
        }

        rule.computeSignatureAndGuard();
        if (ruleRepository.existsByRuleSignatureAndActiveGuard(rule.getRuleSignature(), (byte) 1)) {
            throw new IllegalArgumentException("Quy tắc di chuyển tương ứng đã tồn tại trong chi nhánh.");
        }
        return ruleRepository.save(rule);
    }

    @Transactional
    public void deactivateRule(Long branchId, Long ruleId) {
        BranchRoomTransitionRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc di chuyển."));
        if (!Objects.equals(rule.getBranch().getBranchId(), branchId)) {
            throw new IllegalArgumentException("Quy tắc không thuộc chi nhánh này.");
        }
        rule.setIsActive(false);
        rule.computeSignatureAndGuard();
        ruleRepository.save(rule);
    }

    /**
     * Resolve buffer di chuyển theo thứ tự ưu tiên: SPECIFIC_PAIR > DIFF_FLOOR > SAME_FLOOR_DIFF_ROOM.
     */
    @Transactional(readOnly = true)
    public int resolveBufferMinutes(Long branchId, Room fromRoom, Room toRoom) {
        if (branchId == null || fromRoom == null || toRoom == null
                || Objects.equals(fromRoom.getRoomId(), toRoom.getRoomId())) {
            return 0;
        }
        List<BranchRoomTransitionRule> activeRules = getActiveRules(branchId);

        for (BranchRoomTransitionRule rule : activeRules) {
            if (rule.getRuleScope() == TransitionRuleScope.SPECIFIC_PAIR
                    && rule.getFromRoom() != null && rule.getToRoom() != null
                    && Objects.equals(rule.getFromRoom().getRoomId(), fromRoom.getRoomId())
                    && Objects.equals(rule.getToRoom().getRoomId(), toRoom.getRoomId())) {
                return safeMinutes(rule.getTravelBufferMinutes());
            }
        }

        boolean differentFloor = !Objects.equals(fromRoom.getFloorOrder(), toRoom.getFloorOrder())
                || (fromRoom.getFloorLabel() != null && toRoom.getFloorLabel() != null
                && !fromRoom.getFloorLabel().equalsIgnoreCase(toRoom.getFloorLabel()));

        TransitionRuleScope targetScope = differentFloor
                ? TransitionRuleScope.DIFF_FLOOR
                : TransitionRuleScope.SAME_FLOOR_DIFF_ROOM;

        if (differentFloor) {
            for (BranchRoomTransitionRule rule : activeRules) {
                if (rule.getRuleScope() == TransitionRuleScope.DIFF_FLOOR
                        && matchesFloorLabel(rule.getFromFloorLabel(), fromRoom.getFloorLabel())
                        && matchesFloorLabel(rule.getToFloorLabel(), toRoom.getFloorLabel())
                        && (rule.getFromFloorLabel() != null || rule.getToFloorLabel() != null)) {
                    return safeMinutes(rule.getTravelBufferMinutes());
                }
            }
        }

        for (BranchRoomTransitionRule rule : activeRules) {
            if (rule.getRuleScope() == targetScope
                    && rule.getFromFloorLabel() == null && rule.getToFloorLabel() == null) {
                return safeMinutes(rule.getTravelBufferMinutes());
            }
        }
        return 0;
    }

    private boolean matchesFloorLabel(String ruleLabel, String roomLabel) {
        return ruleLabel == null || (roomLabel != null && ruleLabel.trim().equalsIgnoreCase(roomLabel.trim()));
    }

    private int safeMinutes(Integer minutes) {
        return minutes != null && minutes > 0 ? minutes : 0;
    }

    private String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
