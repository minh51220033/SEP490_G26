package com.cvdcms.repository;

import com.cvdcms.entity.Supplier;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository truy xuất dữ liệu Nhà cung cấp ({@link Supplier}) từ cơ sở dữ liệu.
 *
 * <p><b>Được gọi từ:</b> {@link com.cvdcms.service.impl.SupplierServiceImpl},
 * {@link com.cvdcms.service.impl.SupplierPaymentServiceImpl},
 * {@link com.cvdcms.controller.SupplierPaymentPageController} và các service kho liên quan.</p>
 */
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    /**
     * Kiểm tra mã nhà cung cấp đã tồn tại hay chưa (không phân biệt hoa/thường).
     * Dùng khi tạo mới nhà cung cấp.
     *
     * @param supplierCode mã nhà cung cấp cần kiểm tra
     * @return {@code true} nếu đã tồn tại
     */
    boolean existsBySupplierCodeIgnoreCase(String supplierCode);

    /**
     * Kiểm tra mã nhà cung cấp đã bị nhà cung cấp khác sử dụng hay chưa (loại trừ {@code supplierId}).
     * Dùng khi cập nhật nhà cung cấp.
     *
     * @param supplierCode mã nhà cung cấp mới
     * @param supplierId   ID nhà cung cấp hiện tại cần loại trừ
     * @return {@code true} nếu bị trùng với bản ghi khác
     */
    boolean existsBySupplierCodeIgnoreCaseAndSupplierIdNot(
            String supplierCode, Long supplierId);

    /**
     * Kiểm tra mã số thuế đã tồn tại hay chưa (không phân biệt hoa/thường).
     * Dùng khi tạo mới nhà cung cấp.
     *
     * @param taxCode mã số thuế cần kiểm tra
     * @return {@code true} nếu đã tồn tại
     */
    boolean existsByTaxCodeIgnoreCase(String taxCode);

    /**
     * Kiểm tra mã số thuế đã bị nhà cung cấp khác sử dụng hay chưa (loại trừ {@code supplierId}).
     * Dùng khi cập nhật nhà cung cấp.
     *
     * @param taxCode    mã số thuế mới
     * @param supplierId ID nhà cung cấp hiện tại cần loại trừ
     * @return {@code true} nếu bị trùng với bản ghi khác
     */
    boolean existsByTaxCodeIgnoreCaseAndSupplierIdNot(
            String taxCode, Long supplierId);

    /**
     * Tìm nhà cung cấp theo mã (không phân biệt hoa/thường).
     *
     * @param supplierCode mã nhà cung cấp
     * @return {@link Optional} chứa nhà cung cấp nếu tìm thấy
     */
    Optional<Supplier> findBySupplierCodeIgnoreCase(String supplierCode);

    /**
     * Truy vấn nhà cung cấp kèm khóa bi quan ({@link LockModeType#PESSIMISTIC_WRITE} — {@code SELECT ... FOR UPDATE}).
     *
     * <p><b>Mục đích:</b> Tuần tự hóa các giao dịch thanh toán đồng thời cho cùng một nhà cung cấp trong
     * {@link com.cvdcms.service.impl.SupplierPaymentServiceImpl#createPaymentInternal}, ngăn chặn race condition
     * dẫn đến thanh toán vượt quá tổng công nợ còn lại.</p>
     *
     * @param supplierId ID nhà cung cấp cần khóa
     * @return {@link Optional} chứa bản ghi nhà cung cấp đang được khóa ghi
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Supplier s WHERE s.supplierId = :supplierId")
    Optional<Supplier> findByIdForUpdate(@Param("supplierId") Long supplierId);

    /**
     * Tìm kiếm và phân trang danh sách nhà cung cấp theo từ khóa và trạng thái hoạt động.
     *
     * <p><b>Tiêu chí lọc:</b>
     * <ul>
     *   <li>{@code keyword} (nếu khác {@code null}): khớp gần đúng không phân biệt hoa/thường theo
     *   {@code supplierCode}, {@code supplierName}, {@code phoneNumber} hoặc {@code taxCode}.</li>
     *   <li>{@code active} (nếu khác {@code null}): lọc theo trạng thái {@code isActive}.</li>
     * </ul>
     * </p>
     *
     * @param keyword  từ khóa tìm kiếm (có thể {@code null})
     * @param active   trạng thái hoạt động (có thể {@code null} để lấy tất cả)
     * @param pageable thông tin phân trang và sắp xếp
     * @return trang kết quả {@link Page}&lt;{@link Supplier}&gt;
     */
    @Query("""
        SELECT s FROM Supplier s
        WHERE (:keyword IS NULL
               OR LOWER(s.supplierCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(s.phoneNumber, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(s.taxCode, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:active IS NULL OR s.isActive = :active)
        """)
    Page<Supplier> searchSuppliers(
            @Param("keyword") String keyword,
            @Param("active") Boolean active,
            Pageable pageable);
}
