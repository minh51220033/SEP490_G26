package com.cvdcms.controller;

import com.cvdcms.DTO.MaterialCategoryDTO;
import com.cvdcms.DTO.MaterialCreateDTO;
import com.cvdcms.DTO.MaterialUpdateDTO;
import com.cvdcms.entity.Material;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.MaterialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaterialControllerTest {

    @Mock
    private MaterialService materialService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MaterialController controller;

    private MockHttpSession session;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUserId(5L);
        user.setUsername("admin");
        session = new MockHttpSession();
        session.setAttribute("loggedInUser", user);
    }

    @Test
    void list_ThrowsUnauthorized_WhenSessionMissingOrEmpty() {
        assertThrows(ResponseStatusException.class,
                () -> controller.list("", null, null, 0, 10, "materialName", "asc", null));

        MockHttpSession empty = new MockHttpSession();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.list("", null, null, 0, 10, "materialName", "asc", empty));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void list_ThrowsForbidden_WhenUserLacksSupplierOrWarehouseRole() {
        when(userRepository.hasActiveSupplierManagementRole(5L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.list("", null, null, 0, 10, "materialName", "asc", session));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(materialService);
    }

    @Test
    void list_ThrowsIllegalArgument_WhenPaginationOrSortParamsInvalid() {
        when(userRepository.hasActiveSupplierManagementRole(5L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> controller.list("", null, null, -1, 10, "materialName", "asc", session));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("", null, null, 0, 201, "materialName", "asc", session));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("", null, null, 0, 10, "unknownCol", "asc", session));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list("", null, null, 0, 10, "materialName", "sideways", session));
    }

    @Test
    void list_DelegatesToService_WithValidatedPageable() {
        when(userRepository.hasActiveSupplierManagementRole(5L)).thenReturn(true);
        Page<Material> page = new PageImpl<>(List.of(new Material()));
        when(materialService.search(eq("găng"), eq("Tiêu hao"), eq(true), any(Pageable.class))).thenReturn(page);

        Page<Material> result = controller.list("găng", "Tiêu hao", true, 2, 50, "materialCode", "desc", session);

        assertSame(page, result);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(materialService).search(eq("găng"), eq("Tiêu hao"), eq(true), captor.capture());
        assertEquals(2, captor.getValue().getPageNumber());
        assertEquals(50, captor.getValue().getPageSize());
        assertEquals(Sort.Direction.DESC, captor.getValue().getSort().getOrderFor("materialCode").getDirection());
    }

    @Test
    void listActiveAndGetCategories_ReturnServiceDataWhenAuthorized() {
        when(userRepository.hasActiveSupplierManagementRole(5L)).thenReturn(true);
        Material m = new Material();
        m.setMaterialCode("VT01");
        MaterialCategoryDTO cat = new MaterialCategoryDTO("Thuốc", 3L, 2L);

        when(materialService.listActive()).thenReturn(List.of(m));
        when(materialService.getCategories()).thenReturn(List.of(cat));

        assertEquals(1, controller.listActive(session).size());
        assertEquals(1, controller.getCategories(session).size());
    }

    @Test
    void renameCategory_ReturnsUpdatedSummaryMap() {
        when(userRepository.hasActiveSupplierManagementRole(5L)).thenReturn(true);
        when(materialService.renameCategory("Cũ", "Mới")).thenReturn(4);

        Map<String, Object> res = controller.renameCategory(
                Map.of("oldCategory", "Cũ", "newCategory", "Mới"), session);

        assertEquals("Cũ", res.get("oldCategory"));
        assertEquals("Mới", res.get("newCategory"));
        assertEquals(4, res.get("updatedMaterials"));
    }

    @Test
    void createAndUpdateAndToggleStatus_DelegateToMaterialService() {
        when(userRepository.hasActiveSupplierManagementRole(5L)).thenReturn(true);

        MaterialCreateDTO createDto = new MaterialCreateDTO();
        createDto.setMaterialCode("VT10");
        createDto.setMaterialName("Bông gòn");
        createDto.setCategory("Tiêu hao");
        createDto.setUnit("Gói");
        createDto.setMinStockThreshold(new BigDecimal("10"));

        Material created = new Material();
        created.setMaterialId(10L);
        when(materialService.create(createDto)).thenReturn(created);

        ResponseEntity<Material> createRes = controller.create(createDto, session);
        assertEquals(HttpStatus.CREATED, createRes.getStatusCode());
        assertEquals(10L, createRes.getBody().getMaterialId());

        MaterialUpdateDTO updateDto = new MaterialUpdateDTO();
        updateDto.setMaterialName("Bông gòn y tế");
        when(materialService.update(10L, updateDto)).thenReturn(created);
        when(materialService.getById(10L)).thenReturn(created);
        when(materialService.deactivate(10L)).thenReturn(created);
        when(materialService.activate(10L)).thenReturn(created);

        assertSame(created, controller.detail(10L, session));
        assertSame(created, controller.update(10L, updateDto, session));
        assertSame(created, controller.deactivate(10L, session));
        assertSame(created, controller.activate(10L, session));
    }

    @Test
    void handleBadRequest_ReturnsBadRequestWithMessage() {
        ResponseEntity<Map<String, String>> response = controller.handleBadRequest(new IllegalArgumentException("Mã đã tồn tại"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Mã đã tồn tại", response.getBody().get("message"));
    }
}
