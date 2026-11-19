package com.cvdcms.service;

import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.impl.BranchAccessServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BranchAccessServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BranchService branchService;

    private BranchAccessServiceImpl accessService;

    @BeforeEach
    void setUp() {
        accessService = new BranchAccessServiceImpl(userRepository);
        accessService.setBranchService(branchService);
    }

    @Test
    @DisplayName("canAccessBranch - Trả về false khi userId hoặc branchId null hoặc <= 0")
    void canAccessBranch_invalidIds_returnsFalse() {
        assertFalse(accessService.canAccessBranch(null, 1L));
        assertFalse(accessService.canAccessBranch(0L, 1L));
        assertFalse(accessService.canAccessBranch(-5L, 1L));
        assertFalse(accessService.canAccessBranch(1L, null));
        assertFalse(accessService.canAccessBranch(1L, 0L));
        assertFalse(accessService.canAccessBranch(1L, -1L));
        verifyNoInteractions(userRepository, branchService);
    }

    @Test
    @DisplayName("canAccessBranch - ADMIN đang hoạt động được truy cập mọi chi nhánh hợp lệ")
    void canAccessBranch_activeAdmin_returnsTrue() {
        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);

        assertTrue(accessService.canAccessBranch(1L, 99L));
        verify(userRepository, never()).countActiveWarehouseAssignments(any(), any());
    }

    @Test
    @DisplayName("canAccessBranch - Nhân viên có phân công kho ACTIVE tại chi nhánh được phép truy cập")
    void canAccessBranch_activeWarehouseAssignment_returnsTrue() {
        when(userRepository.hasActiveAdminRole(10L)).thenReturn(false);
        when(userRepository.countActiveWarehouseAssignments(10L, 2L)).thenReturn(1L);

        assertTrue(accessService.canAccessBranch(10L, 2L));
        verify(userRepository, never()).countActiveInventoryPermissionForBranch(any(), any());
    }

    @Test
    @DisplayName("canAccessBranch - Nhân viên có quyền INVENTORY tại chi nhánh được phép truy cập")
    void canAccessBranch_activeInventoryPermission_returnsTrue() {
        when(userRepository.hasActiveAdminRole(11L)).thenReturn(false);
        when(userRepository.countActiveWarehouseAssignments(11L, 2L)).thenReturn(0L);
        when(userRepository.countActiveInventoryPermissionForBranch(11L, 2L)).thenReturn(2L);

        assertTrue(accessService.canAccessBranch(11L, 2L));
        verifyNoInteractions(branchService);
    }

    @Test
    @DisplayName("canAccessBranch - Cho phép qua fallback SupplierManagementRole khi branchService cho phép")
    void canAccessBranch_supplierManagementFallbackAllowed_returnsTrue() {
        when(userRepository.hasActiveAdminRole(12L)).thenReturn(false);
        when(userRepository.countActiveWarehouseAssignments(12L, 3L)).thenReturn(null);
        when(userRepository.countActiveInventoryPermissionForBranch(12L, 3L)).thenReturn(0L);
        when(userRepository.hasActiveSupplierManagementRole(12L)).thenReturn(true);
        when(branchService.canUserAccessBranch(12L, 3L)).thenReturn(true);

        assertTrue(accessService.canAccessBranch(12L, 3L));
    }

    @Test
    @DisplayName("canAccessBranch - Từ chối khi không có phân công, không có quyền kho và không qua được fallback")
    void canAccessBranch_noPermissions_returnsFalse() {
        when(userRepository.hasActiveAdminRole(13L)).thenReturn(false);
        when(userRepository.countActiveWarehouseAssignments(13L, 3L)).thenReturn(0L);
        when(userRepository.countActiveInventoryPermissionForBranch(13L, 3L)).thenReturn(0L);
        when(userRepository.hasActiveSupplierManagementRole(13L)).thenReturn(false);

        assertFalse(accessService.canAccessBranch(13L, 3L));
        verifyNoInteractions(branchService);
    }

    @Test
    @DisplayName("canAccessBranch - Trả về false an toàn khi branchService chưa được inject")
    void canAccessBranch_nullBranchService_returnsFalse() {
        BranchAccessServiceImpl serviceWithoutBranch = new BranchAccessServiceImpl(userRepository);
        when(userRepository.hasActiveAdminRole(14L)).thenReturn(false);
        when(userRepository.countActiveWarehouseAssignments(14L, 1L)).thenReturn(0L);
        when(userRepository.countActiveInventoryPermissionForBranch(14L, 1L)).thenReturn(0L);

        assertFalse(serviceWithoutBranch.canAccessBranch(14L, 1L));
    }

    @Test
    @DisplayName("isAdmin - Kiểm tra id null/<=0 trả false và ủy quyền cho UserRepository")
    void isAdmin_validAndInvalidUsers() {
        assertFalse(accessService.isAdmin(null));
        assertFalse(accessService.isAdmin(0L));
        assertFalse(accessService.isAdmin(-2L));

        when(userRepository.hasActiveAdminRole(1L)).thenReturn(true);
        when(userRepository.hasActiveAdminRole(2L)).thenReturn(false);

        assertTrue(accessService.isAdmin(1L));
        assertFalse(accessService.isAdmin(2L));
    }
}
