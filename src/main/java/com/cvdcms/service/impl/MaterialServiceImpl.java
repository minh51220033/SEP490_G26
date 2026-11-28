package com.cvdcms.service.impl;

import com.cvdcms.DTO.MaterialCategoryDTO;
import com.cvdcms.DTO.MaterialCreateDTO;
import com.cvdcms.DTO.MaterialUpdateDTO;
import com.cvdcms.entity.Material;
import com.cvdcms.repository.MaterialRepository;
import com.cvdcms.service.MaterialService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Triển khai nghiệp vụ quản lý Danh mục vật tư nha khoa ({@link MaterialService}).
 * <p>Đảm bảo chuẩn hóa mã vật tư (chữ hoa), chống trùng mã, bảo toàn tính nhất quán với danh mục thuốc đồng bộ
 * ({@code material.getMedication()}) và cung cấp bộ nhóm vật tư mặc định cho giao diện.</p>
 * <p>Được gọi từ {@link com.cvdcms.controller.MaterialController}.</p>
 */
@Service
public class MaterialServiceImpl implements MaterialService {

    /** Danh sách các nhóm vật tư nha khoa mặc định luôn hiển thị trong bộ lọc và dropdown chọn nhóm. */
    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "Trám răng",
            "Chỉnh nha",
            "Thuốc tê",
            "Tiêu hao",
            "Nội nha",
            "Phục hình",
            "Cấy ghép Implant",
            "Vật tư vô trùng"
    );

    private final MaterialRepository materialRepository;

    /** Khởi tạo service với repository danh mục vật tư. */
    public MaterialServiceImpl(MaterialRepository materialRepository) {
        this.materialRepository = materialRepository;
    }

    /**
     * Chuẩn hóa từ khóa/nhóm vật tư và gọi {@link MaterialRepository#searchMaterials} để phân trang danh mục.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<Material> search(
            String keyword,
            String category,
            Boolean active,
            Pageable pageable) {

        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        String normalizedCategory = (category == null || category.isBlank()) ? null : category.trim();

        return materialRepository.searchMaterials(
                normalizedKeyword,
                normalizedCategory,
                active,
                pageable
        );
    }

    /** Trả về danh sách vật tư đang hoạt động ({@code isActive = true}) sắp theo tên A–Z. */
    @Override
    @Transactional(readOnly = true)
    public List<Material> listActive() {
        return materialRepository.findByIsActiveTrueOrderByMaterialNameAsc();
    }

    /**
     * Tổng hợp số lượng vật tư (tổng và đang hoạt động) theo từng nhóm từ CSDL,
     * sau đó gộp thêm {@link #DEFAULT_CATEGORIES} nếu nhóm mặc định chưa có dữ liệu.
     */
    @Override
    @Transactional(readOnly = true)
    public List<MaterialCategoryDTO> getCategories() {
        Map<String, MaterialCategoryDTO> map = new LinkedHashMap<>();

        for (Object[] row : materialRepository.countMaterialsByCategory()) {
            String catName = (String) row[0];
            long total = row[1] == null ? 0L : ((Number) row[1]).longValue();
            long active = row[2] == null ? 0L : ((Number) row[2]).longValue();
            map.put(catName.toLowerCase(Locale.ROOT), new MaterialCategoryDTO(catName, total, active));
        }

        for (String defaultCat : DEFAULT_CATEGORIES) {
            map.putIfAbsent(
                    defaultCat.toLowerCase(Locale.ROOT),
                    new MaterialCategoryDTO(defaultCat, 0L, 0L)
            );
        }

        return new ArrayList<>(map.values());
    }

    /**
     * Đổi tên nhóm vật tư hàng loạt (so khớp không phân biệt hoa thường) cho tất cả vật tư thuộc {@code oldCategory}.
     */
    @Override
    @Transactional
    public int renameCategory(String oldCategory, String newCategory) {
        String oldCat = normalizeRequired(oldCategory, "Tên nhóm vật tư hiện tại");
        String newCat = normalizeRequired(newCategory, "Tên nhóm vật tư mới");

        List<Material> all = materialRepository.findAll();
        int updated = 0;
        for (Material m : all) {
            if (m.getCategory() != null && m.getCategory().trim().equalsIgnoreCase(oldCat)) {
                m.setCategory(newCat);
                materialRepository.save(m);
                updated++;
            }
        }
        return updated;
    }

    /** Tra cứu chi tiết vật tư theo {@code id}; báo lỗi nếu {@code id <= 0} hoặc không tồn tại. */
    @Override
    @Transactional(readOnly = true)
    public Material getById(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Mã định danh vật tư không hợp lệ.");
        }
        return materialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vật tư."));
    }

    /**
     * Tạo mới vật tư: chuẩn hóa mã chữ hoa, kiểm tra không trùng mã, chuẩn hóa tên/nhóm/đơn vị/ngưỡng tối thiểu
     * và đặt mặc định {@code isActive = true}.
     */
    @Override
    @Transactional
    public Material create(MaterialCreateDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu vật tư không được để trống.");
        }

        String code = normalizeCode(dto.getMaterialCode());
        if (materialRepository.existsByMaterialCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("Mã vật tư đã tồn tại.");
        }

        Material material = new Material();
        material.setMaterialCode(code);
        material.setMaterialName(normalizeRequired(dto.getMaterialName(), "Tên vật tư"));
        material.setCategory(normalizeRequired(dto.getCategory(), "Nhóm vật tư"));
        material.setUnit(normalizeRequired(dto.getUnit(), "Đơn vị tính"));
        material.setMinStockThreshold(normalizeThreshold(dto.getMinStockThreshold()));
        material.setIsActive(true);

        return materialRepository.save(material);
    }

    /**
     * Cập nhật vật tư: chặn sửa mã/tên/đơn vị nếu mặt hàng được đồng bộ từ danh mục thuốc ({@code medication != null}),
     * kiểm tra trùng mã với vật tư khác và lưu thông tin mới.
     */
    @Override
    @Transactional
    public Material update(Long id, MaterialUpdateDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu vật tư không được để trống.");
        }

        Material material = getById(id);
        String code = normalizeCode(dto.getMaterialCode());

        // Mặt hàng đồng bộ từ danh mục thuốc: mã, tên, đơn vị do Admin quản lý tại /admin/medications.
        if (material.getMedication() != null) {
            String newName = normalizeRequired(dto.getMaterialName(), "Tên vật tư");
            String newUnit = normalizeRequired(dto.getUnit(), "Đơn vị tính");
            if (!code.equalsIgnoreCase(material.getMaterialCode())
                    || !newName.equals(material.getMaterialName())
                    || !newUnit.equals(material.getUnit())) {
                throw new IllegalArgumentException(
                        "Mặt hàng này được đồng bộ từ danh mục thuốc. Mã, tên và đơn vị chỉ được sửa tại trang Quản lý danh mục thuốc.");
            }
        }

        if (materialRepository.existsByMaterialCodeIgnoreCaseAndMaterialIdNot(code, id)) {
            throw new IllegalArgumentException("Mã vật tư đã được sử dụng bởi vật tư khác.");
        }

        material.setMaterialCode(code);
        material.setMaterialName(normalizeRequired(dto.getMaterialName(), "Tên vật tư"));
        material.setCategory(normalizeRequired(dto.getCategory(), "Nhóm vật tư"));
        material.setUnit(normalizeRequired(dto.getUnit(), "Đơn vị tính"));
        material.setMinStockThreshold(normalizeThreshold(dto.getMinStockThreshold()));

        return materialRepository.save(material);
    }

    /** Chuyển trạng thái vật tư sang ngừng sử dụng ({@code isActive = false}); báo lỗi nếu đã ngừng trước đó. */
    @Override
    @Transactional
    public Material deactivate(Long id) {
        Material material = getById(id);
        if (Boolean.FALSE.equals(material.getIsActive())) {
            throw new IllegalArgumentException("Vật tư đã ở trạng thái ngừng sử dụng.");
        }
        material.setIsActive(false);
        return materialRepository.save(material);
    }

    /**
     * Kích hoạt lại vật tư ({@code isActive = true}).
     * Nếu vật tư liên kết với danh mục thuốc và thuốc gốc đang ngừng sử dụng, từ chối kích hoạt để giữ nhất quán dữ liệu.
     */
    @Override
    @Transactional
    public Material activate(Long id) {
        Material material = getById(id);
        // Không kích hoạt lại mặt hàng kho khi thuốc gốc đang ngừng sử dụng (giữ trạng thái nhất quán).
        if (material.getMedication() != null && !Boolean.TRUE.equals(material.getMedication().getIsActive())) {
            throw new IllegalArgumentException(
                    "Thuốc gốc đang ngừng sử dụng. Hãy kích hoạt lại thuốc tại trang Quản lý danh mục thuốc.");
        }
        if (Boolean.TRUE.equals(material.getIsActive())) {
            throw new IllegalArgumentException("Vật tư đang hoạt động.");
        }
        material.setIsActive(true);
        return materialRepository.save(material);
    }

    /** Chuẩn hóa mã vật tư: cắt khoảng trắng và chuyển sang chữ in hoa; báo lỗi nếu rỗng. */
    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Mã vật tư không được để trống.");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /** Chuẩn hóa trường văn bản bắt buộc: cắt khoảng trắng đầu cuối và gộp nhiều khoảng trắng liên tiếp thành 1. */
    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " không được để trống.");
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    /** Chuẩn hóa ngưỡng tồn tối thiểu: mặc định {@code 10.000} nếu null, không cho phép âm, làm tròn 3 chữ số thập phân. */
    private BigDecimal normalizeThreshold(BigDecimal value) {
        if (value == null) {
            return new BigDecimal("10.000");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Ngưỡng tồn tối thiểu không được âm.");
        }
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
