# QUY CHUẨN KỸ THUẬT: FOOTER PHÂN TRANG (ENTERPRISE MISA AMIS)

Mọi màn hình hiển thị danh sách dữ liệu (Data Grid / Table) trong hệ thống CV-DCMS bắt buộc phải áp dụng quy chuẩn phân trang chân bảng (Table Footer) theo phong cách Enterprise MISA AMIS.

## 1. Backend Controller Contract
Mọi Spring MVC Controller trả về view danh sách phải tiếp nhận tham số `page` (default 0) và `size` (default 10, chỉ chấp nhận 10, 25, 50, 100), đồng thời tính toán và truyền đủ 6 thuộc tính bắt buộc vào `Model`:
- `items`: Danh sách phần tử trên trang hiện tại (`resultPage.getContent()`).
- `totalItems`: Tổng số bản ghi thỏa mãn bộ lọc (`long`).
- `currentPage`: Trang hiện tại 0-indexed (`int`).
- `pageSize`: Số dòng trên trang (`int`).
- `totalPages`: Tổng số trang (`int`).
- `fromItem`: Vị trí bản ghi bắt đầu hiển thị 1-indexed. Khi `totalItems == 0` là `0`, ngược lại `Math.min(((long) currentPage * size) + 1, toItem)`.
- `toItem`: Vị trí bản ghi kết thúc hiển thị `Math.min(((long) (currentPage + 1) * size), totalItems)`.
- `keyword`: Từ khóa tìm kiếm nếu có.

### Xử lý an toàn khi `currentPage` vượt quá `totalPages`:
```java
if (currentPage >= totalPages && totalPages > 0) {
    currentPage = totalPages - 1;
    // Tự động lùi về trang cuối hợp lệ
}
```

## 2. Frontend HTML (Thymeleaf)
Đặt khối phân trang ngay dưới thẻ `</table>` trong khối bọc bảng dữ liệu (`.staff-table-card` hoặc tương đương):
```html
<!-- FOOTER PHÂN TRANG CHUẨN MISA AMIS -->
<div class="misa-pagination-footer">
    <!-- Bên trái: Tổng số -->
    <div class="misa-pagination-left">
        Tổng số: <strong th:text="${totalItems}">0</strong>
    </div>
    <!-- Bên phải: Số dòng/trang + Range + 4 Nút điều hướng -->
    <div class="misa-pagination-right">
        <!-- Dropdown chọn số dòng/trang -->
        <div class="misa-page-size-wrapper">
            <span>Số dòng/trang</span>
            <select id="pageSizeSelect" class="form-select form-select-sm misa-page-size-select" aria-label="Số dòng trên mỗi trang">
                <option value="10" th:selected="${pageSize == 10}">10</option>
                <option value="25" th:selected="${pageSize == 25}">25</option>
                <option value="50" th:selected="${pageSize == 50}">50</option>
                <option value="100" th:selected="${pageSize == 100}">100</option>
            </select>
        </div>
        <!-- Khoảng hiển thị bản ghi (Ví dụ: 1 - 10) -->
        <div class="misa-page-range">
            <span th:text="${fromItem}">0</span> - <span th:text="${toItem}">0</span>
        </div>
        <!-- Cụm 4 nút phân trang (|< < > >|) -->
        <div class="d-flex align-items-center gap-1">
            <!-- 1. Về trang đầu -->
            <a th:href="@{/endpoint(page=0, size=${pageSize}, keyword=${keyword})}"
               class="misa-nav-btn"
               th:classappend="${currentPage == 0} ? 'disabled' : ''"
               title="Trang đầu">
                <i class="bi bi-chevron-bar-left"></i>
            </a>
            <!-- 2. Trang trước -->
            <a th:href="@{/endpoint(page=${currentPage - 1}, size=${pageSize}, keyword=${keyword})}"
               class="misa-nav-btn"
               th:classappend="${currentPage == 0} ? 'disabled' : ''"
               title="Trang trước">
                <i class="bi bi-chevron-left"></i>
            </a>
            <!-- 3. Trang sau -->
            <a th:href="@{/endpoint(page=${currentPage + 1}, size=${pageSize}, keyword=${keyword})}"
               class="misa-nav-btn"
               th:classappend="${currentPage >= totalPages - 1 or totalPages == 0} ? 'disabled' : ''"
               title="Trang sau">
                <i class="bi bi-chevron-right"></i>
            </a>
            <!-- 4. Đến trang cuối -->
            <a th:href="@{/endpoint(page=${totalPages > 0 ? totalPages - 1 : 0}, size=${pageSize}, keyword=${keyword})}"
               class="misa-nav-btn"
               th:classappend="${currentPage >= totalPages - 1 or totalPages == 0} ? 'disabled' : ''"
               title="Trang cuối">
                <i class="bi bi-chevron-bar-right"></i>
            </a>
        </div>
    </div>
</div>
```

## 3. Form Lọc & JavaScript Điều Khiển
1. Form tìm kiếm/lọc bắt buộc chứa 2 input ẩn:
```html
<form id="filterForm" method="get" th:action="@{/endpoint}">
    <input type="text" name="keyword" th:value="${keyword}">
    <!-- Bắt buộc có 2 trường ẩn này -->
    <input type="hidden" id="pageSizeInput" name="size" th:value="${pageSize}">
    <input type="hidden" id="pageIndexInput" name="page" th:value="${currentPage}">
</form>
```

2. Khối JavaScript xử lý:
```javascript
document.addEventListener('DOMContentLoaded', function () {
    const pageSizeSelect = document.getElementById('pageSizeSelect');
    const filterForm = document.getElementById('filterForm');
    const pageSizeInput = document.getElementById('pageSizeInput');
    const pageIndexInput = document.getElementById('pageIndexInput');
    if (filterForm) {
        filterForm.addEventListener('submit', function () {
            if (pageIndexInput) {
                pageIndexInput.value = '0'; // Bắt buộc reset về trang đầu khi gửi filter mới
            }
        });
    }
    if (pageSizeSelect && filterForm && pageSizeInput) {
        pageSizeSelect.addEventListener('change', function () {
            pageSizeInput.value = this.value;
            if (pageIndexInput) {
                pageIndexInput.value = '0'; // Bắt buộc reset về trang đầu
            }
            filterForm.submit();
        });
    }
});
```

## 4. Bảng Checklist Kiểm Thử
| STT | Tình huống kiểm thử (Test Case) | Kết quả mong đợi |
| --- | --- | --- |
| 1 | Dữ liệu rỗng (0 bản ghi) | Hiển thị 'Tổng số: 0', khoảng '0 - 0', cả 4 nút `\|< < > >\|` đều bị vô hiệu hóa (disabled). |
| 2 | Tổng số bản ghi < số dòng/trang (VD: 6 bản ghi, size 10) | Hiển thị 'Tổng số: 6', khoảng '1 - 6', cả 4 nút điều hướng đều disabled. |
| 3 | Đang ở trang 1 của danh sách nhiều trang (page = 0) | Nút `\|<` và `<` bị disabled. Nút `>` và `>\|` cho phép bấm chuyển tiếp. |
| 4 | Đang ở trang cuối cùng của danh sách | Nút `>` và `>\|` bị disabled. Nút `\|<` và `<` cho phép bấm quay lại. |
| 5 | Đổi 'Số dòng/trang' (VD: từ 10 lên 25 hoặc 50) | Trang tự reload, số dòng cập nhật đúng, tự động reset về trang 1, bảo lưu từ khóa tìm kiếm và bộ lọc. |
| 6 | Bấm chuyển trang khi đang lọc theo keyword/chi nhánh | Chuyển trang bình thường, URL vẫn duy trì đầy đủ các tham số lọc. |
| 7 | Kiểm tra font chữ và độ hiển thị trên màn hình | Toàn bộ dùng font Be Vietnam Pro / Inter, không bị co dúm hay vỡ giao diện trên laptop từ 13.3 inch. |

