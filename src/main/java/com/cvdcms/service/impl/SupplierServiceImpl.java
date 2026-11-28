
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

/**
 * Triển khai nghiệp vụ quản lý Nhà cung cấp ({@link SupplierService}).
 *
 * <p><b>Trách nhiệm chính:</b>
 * <ul>
 *   <li>Tìm kiếm, lọc trạng thái và phân trang danh mục nhà cung cấp.</li>
 *   <li>Chuẩn hóa dữ liệu (mã NCC viết hoa, tên gọn khoảng trắng, trường tùy chọn rỗng chuyển về {@code null}).</li>
 *   <li>Đảm bảo tính duy nhất không phân biệt hoa/thường cho {@code supplierCode} và {@code taxCode} khi tạo/sửa.</li>
 *   <li>Hỗ trợ ngừng hoạt động / kích hoạt lại (soft status) thay vì xóa cứng để bảo toàn lịch sử nhập kho và công nợ.</li>
 * </ul>
 * </p>
 *
 * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierController}.</p>
 */
@Service
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository supplierRepository;

    public SupplierServiceImpl(
            SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    /**
     * Tìm kiếm danh sách nhà cung cấp có lọc trạng thái và phân trang.
     *
     * <p><b>Xử lý:</b> Chuẩn hóa {@code keyword} rỗng/khoảng trắng về {@code null} rồi gọi
     * {@link SupplierRepository#searchSuppliers}.</p>
     *
     * @param keyword  từ khóa tìm kiếm
     * @param active   trạng thái hoạt động (có thể {@code null})
     * @param pageable thông tin phân trang và sắp xếp
     * @return trang kết quả {@link Page}&lt;{@link Supplier}&gt;
     */
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

    /**
     * Lấy chi tiết nhà cung cấp theo ID.
     *
     * <p><b>Xử lý:</b> Kiểm tra {@code id > 0}, truy vấn {@link SupplierRepository#findById};
     * ném {@link IllegalArgumentException} nếu ID không hợp lệ hoặc không tìm thấy bản ghi.</p>
     *
     * @param id ID nhà cung cấp
     * @return bản ghi {@link Supplier}
     */
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

    /**
     * Tạo mới một nhà cung cấp.
     *
     * <p><b>Xử lý từng bước:</b>
     * <ol>
     *   <li>Kiểm tra {@code dto != null} và chuẩn hóa {@code supplierCode} sang chữ hoa.</li>
     *   <li>Kiểm tra trùng lặp {@code supplierCode} và {@code taxCode} (nếu có) không phân biệt hoa/thường.</li>
     *   <li>Chuẩn hóa {@code supplierName}, {@code phoneNumber}, {@code address}, gán {@code isActive = true} và lưu DB.</li>
     * </ol>
     * </p>
     *
     * @param dto dữ liệu tạo mới nhà cung cấp
     * @return bản ghi {@link Supplier} sau khi lưu
     */
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

        String taxCode = normalizeOptional(dto.getTaxCode());
        if (taxCode != null && supplierRepository.existsByTaxCodeIgnoreCase(taxCode)) {
            throw new IllegalArgumentException(
                    "Mã số thuế đã được sử dụng bởi nhà cung cấp khác.");
        }

        Supplier supplier = new Supplier();
        supplier.setSupplierCode(code);
        supplier.setSupplierName(
                normalizeRequired(
                        dto.getSupplierName(),
                        "Tên nhà cung cấp"
                )
        );
        supplier.setTaxCode(taxCode);
        supplier.setPhoneNumber(
                normalizeOptional(dto.getPhoneNumber()));
        supplier.setAddress(normalizeOptional(dto.getAddress()));
        supplier.setIsActive(true);

        return supplierRepository.save(supplier);
    }

    /**
     * Cập nhật thông tin nhà cung cấp hiện có.
     *
     * <p><b>Xử lý từng bước:</b>
     * <ol>
     *   <li>Nạp bản ghi hiện tại qua {@link #getById(Long)}.</li>
     *   <li>Chuẩn hóa {@code supplierCode} và {@code taxCode}, kiểm tra không trùng với bất kỳ nhà cung cấp nào khác ({@code supplierId != id}).</li>
     *   <li>Cập nhật các trường thông tin; giữ nguyên trạng thái {@code isActive} hiện có.</li>
     * </ol>
     * </p>
     *
     * @param id  ID nhà cung cấp cần cập nhật
     * @param dto dữ liệu cập nhật
     * @return bản ghi {@link Supplier} sau khi lưu
     */
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

        String taxCode = normalizeOptional(dto.getTaxCode());
        if (taxCode != null && supplierRepository
                .existsByTaxCodeIgnoreCaseAndSupplierIdNot(taxCode, id)) {
            throw new IllegalArgumentException(
                    "Mã số thuế đã được sử dụng bởi nhà cung cấp khác.");
        }

        supplier.setSupplierCode(code);
        supplier.setSupplierName(
                normalizeRequired(
                        dto.getSupplierName(),
                        "Tên nhà cung cấp"
                )
        );
        supplier.setTaxCode(taxCode);
        supplier.setPhoneNumber(
                normalizeOptional(dto.getPhoneNumber()));
        supplier.setAddress(normalizeOptional(dto.getAddress()));

        // Không thay đổi trạng thái isActive tại đây.
        return supplierRepository.save(supplier);
    }

    /**
     * Ngừng hoạt động nhà cung cấp ({@code isActive = false}), giữ nguyên lịch sử phiếu nhập kho và thanh toán.
     * Ném {@link IllegalArgumentException} nếu nhà cung cấp đã ở trạng thái ngừng hoạt động.
     *
     * @param id ID nhà cung cấp
     * @return bản ghi {@link Supplier} sau khi cập nhật
     */
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

    /**
     * Kích hoạt lại nhà cung cấp ({@code isActive = true}).
     * Ném {@link IllegalArgumentException} nếu nhà cung cấp đang ở trạng thái hoạt động.
     *
     * @param id ID nhà cung cấp
     * @return bản ghi {@link Supplier} sau khi cập nhật
     */
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

    /**
     * Chuẩn hóa chuỗi bắt buộc: kiểm tra không rỗng, cắt khoảng trắng đầu/cuối và gộp nhiều khoảng trắng liên tiếp thành 1 dấu cách.
     *
     * @param value     giá trị đầu vào
     * @param fieldName tên trường dùng trong thông báo lỗi
     * @return chuỗi đã chuẩn hóa
     */
    private String normalizeRequired(
            String value,
            String fieldName) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " không được để trống.");
        }

        return value.trim().replaceAll("\\s+", " ");
    }

    /**
     * Chuẩn hóa mã nhà cung cấp: kiểm tra không rỗng, cắt khoảng trắng và chuyển sang chữ IN HOA theo {@link Locale#ROOT}.
     *
     * @param code mã nhà cung cấp thô
     * @return mã nhà cung cấp đã chuẩn hóa chữ hoa
     */
    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(
                    "Mã nhà cung cấp không được để trống.");
        }

        return code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Chuẩn hóa chuỗi tùy chọn: trả về {@code null} nếu rỗng/chỉ chứa khoảng trắng, ngược lại trả về chuỗi đã {@code trim()}.
     *
     * @param value giá trị đầu vào
     * @return chuỗi đã cắt khoảng trắng hoặc {@code null}
     */
    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
