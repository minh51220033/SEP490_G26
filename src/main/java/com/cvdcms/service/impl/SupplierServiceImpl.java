
package com.cvdcms.service.impl;

import com.cvdcms.DTO.SupplierCreateDTO;
import com.cvdcms.DTO.SupplierUpdateDTO;
import com.cvdcms.entity.Supplier;
import com.cvdcms.repository.SupplierRepository;
import com.cvdcms.service.SupplierService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository supplierRepository;

    public SupplierServiceImpl(
            SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    // Danh sách nhà cung cấp có tìm kiếm, lọc và phân trang.
    @Override
    @Transactional(readOnly = true)
    public Page<Supplier> search(
            String keyword,
            Boolean active,
            Pageable pageable) {

        String normalizedKeyword =
                (keyword == null || keyword.isBlank())
                        ? null
                        : keyword.trim();

        return supplierRepository.searchSuppliers(
                normalizedKeyword,
                active,
                pageable
        );
    }

    // Xem chi tiết nhà cung cấp.
    @Override
    @Transactional(readOnly = true)
    public Supplier getById(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(
                    "Mã nhà cung cấp không hợp lệ.");
        }

        return supplierRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy nhà cung cấp."));
    }

    // Thêm nhà cung cấp mới.
    @Override
    @Transactional
    public Supplier create(SupplierCreateDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException(
                    "Dữ liệu nhà cung cấp không được để trống.");
        }

        String code = normalizeCode(dto.getSupplierCode());

        if (supplierRepository.existsBySupplierCodeIgnoreCase(code)) {
            throw new IllegalArgumentException(
                    "Mã nhà cung cấp đã tồn tại.");
        }

        Supplier supplier = new Supplier();
        supplier.setSupplierCode(code);
        supplier.setSupplierName(
                normalizeRequired(
                        dto.getSupplierName(),
                        "Tên nhà cung cấp"
                )
        );
        supplier.setTaxCode(normalizeOptional(dto.getTaxCode()));
        supplier.setPhoneNumber(
                normalizeOptional(dto.getPhoneNumber()));
        supplier.setAddress(normalizeOptional(dto.getAddress()));
        supplier.setIsActive(true);

        return supplierRepository.save(supplier);
    }

    // Cập nhật thông tin nhà cung cấp.
    @Override
    @Transactional
    public Supplier update(Long id, SupplierUpdateDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException(
                    "Dữ liệu nhà cung cấp không được để trống.");
        }

        Supplier supplier = getById(id);
        String code = normalizeCode(dto.getSupplierCode());

        if (supplierRepository
                .existsBySupplierCodeIgnoreCaseAndSupplierIdNot(
                        code, id)) {
            throw new IllegalArgumentException(
                    "Mã nhà cung cấp đã được sử dụng.");
        }

        supplier.setSupplierCode(code);
        supplier.setSupplierName(
                normalizeRequired(
                        dto.getSupplierName(),
                        "Tên nhà cung cấp"
                )
        );
        supplier.setTaxCode(normalizeOptional(dto.getTaxCode()));
        supplier.setPhoneNumber(
                normalizeOptional(dto.getPhoneNumber()));
        supplier.setAddress(normalizeOptional(dto.getAddress()));

        // Không thay đổi trạng thái isActive tại đây.
        return supplierRepository.save(supplier);
    }

    // Ngừng hoạt động, giữ lịch sử liên quan đến phiếu nhập kho.
    @Override
    @Transactional
    public Supplier deactivate(Long id) {
        Supplier supplier = getById(id);

        if (Boolean.FALSE.equals(supplier.getIsActive())) {
            throw new IllegalArgumentException(
                    "Nhà cung cấp đã ngừng hoạt động.");
        }

        supplier.setIsActive(false);
        return supplierRepository.save(supplier);
    }

    // Kích hoạt lại nhà cung cấp.
    @Override
    @Transactional
    public Supplier activate(Long id) {
        Supplier supplier = getById(id);

        if (Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new IllegalArgumentException(
                    "Nhà cung cấp đang hoạt động.");
        }

        supplier.setIsActive(true);
        return supplierRepository.save(supplier);
    }

    // Chuẩn hóa dữ liệu bắt buộc.
    private String normalizeRequired(
            String value,
            String fieldName) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " không được để trống.");
        }

        return value.trim().replaceAll("\\s+", " ");
    }

    // Chuẩn hóa mã nhà cung cấp.
    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(
                    "Mã nhà cung cấp không được để trống.");
        }

        return code.trim().toUpperCase(Locale.ROOT);
    }

    // Chuỗi tùy chọn để trống sẽ được lưu thành null.
    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
