package com.cvdcms.service;

import com.cvdcms.entity.AttendanceRecord;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.StaffSchedule;
import com.cvdcms.entity.enums.AttendanceStatus;
import com.cvdcms.entity.enums.ScheduleStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.AttendanceRecordRepository;
import com.cvdcms.repository.StaffProfileRepository;
import com.cvdcms.repository.StaffScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// FT33 - Chấm công: Check-in / Check-out (SCR-33.2) và lịch sử chấm công (SCR-33.4).
@Service
public class AttendanceService {

    // Cho phép trễ / về sớm tối đa số phút này mà vẫn tính là đúng giờ.
    private static final int GRACE_MINUTES = 15;

    private final AttendanceRecordRepository attendanceRepository;
    private final StaffScheduleRepository scheduleRepository;
    private final StaffProfileRepository staffRepository;

    public AttendanceService(AttendanceRecordRepository attendanceRepository,
            StaffScheduleRepository scheduleRepository,
            StaffProfileRepository staffRepository) {
        this.attendanceRepository = attendanceRepository;
        this.scheduleRepository = scheduleRepository;
        this.staffRepository = staffRepository;
    }

    @Transactional(readOnly = true)
    public StaffProfile findStaffByUserId(Long userId) {
        return staffRepository.findByUserUserId(userId).orElse(null);
    }

    // Các ca SCHEDULED của nhân viên trong ngày (đã sắp theo giờ bắt đầu).
    @Transactional(readOnly = true)
    public List<StaffSchedule> getTodayShifts(Long staffId, LocalDate date) {
        List<StaffSchedule> result = new ArrayList<>();
        for (StaffSchedule s : scheduleRepository.findByStaffAndRange(staffId, date, date)) {
            if (s.getStatus() == ScheduleStatus.SCHEDULED) {
                result.add(s);
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public AttendanceRecord findRecord(Long staffId, LocalDate date) {
        return attendanceRepository.findByStaffStaffIdAndWorkDate(staffId, date).orElse(null);
    }

    // ===================== SCR-33.2: CHECK-IN / CHECK-OUT =====================

    @Transactional
    public AttendanceRecord checkIn(Long userId, String clientIp) {
        StaffProfile staff = requireStaff(userId);
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        List<StaffSchedule> shifts = getTodayShifts(staff.getStaffId(), today);
        if (shifts.isEmpty()) {
            throw new IllegalArgumentException("Hôm nay bạn chưa được xếp ca làm việc nên không thể check-in.");
        }

        AttendanceRecord existing = findRecord(staff.getStaffId(), today);
        if (existing != null && existing.getCheckInAt() != null) {
            throw new IllegalArgumentException("Bạn đã check-in hôm nay rồi.");
        }

        // Nếu có nhiều ca trong ngày (ví dụ ca sáng chi nhánh A, ca tối chi nhánh B),
        // ưu tiên ca mà nhân viên đang kết nối đúng Wifi của chi nhánh đó.
        StaffSchedule target = shifts.get(0);
        if (shifts.size() > 1) {
            for (StaffSchedule s : shifts) {
                if (isAllowedIp(s.getBranch().getAllowedIpRange(), clientIp)) {
                    target = s;
                    break;
                }
            }
        }

        Branch branch = target.getBranch();
        requireValidNetwork(branch, clientIp);

        AttendanceRecord record = new AttendanceRecord();
        record.setStaff(staff);
        record.setBranch(branch);
        record.setWorkDate(today);
        record.setCheckInAt(now);
        record.setCheckInIp(clientIp);
        record.setIsValidNetwork(true);

        LocalTime lateAfter = target.getShift().getStartTime().plusMinutes(GRACE_MINUTES);
        record.setStatus(now.toLocalTime().isAfter(lateAfter) ? AttendanceStatus.LATE : AttendanceStatus.ON_TIME);
        return attendanceRepository.save(record);
    }

    @Transactional
    public AttendanceRecord checkOut(Long userId, String clientIp) {
        StaffProfile staff = requireStaff(userId);
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        AttendanceRecord record = findRecord(staff.getStaffId(), today);
        if (record == null || record.getCheckInAt() == null) {
            throw new IllegalArgumentException("Bạn chưa check-in hôm nay nên không thể check-out.");
        }
        if (record.getCheckOutAt() != null) {
            throw new IllegalArgumentException("Bạn đã check-out hôm nay rồi.");
        }
        requireValidNetwork(record.getBranch(), clientIp);

        record.setCheckOutAt(now);
        record.setCheckOutIp(clientIp);
        record.setWorkedMinutes((int) Math.max(0, Duration.between(record.getCheckInAt(), now).toMinutes()));

        // Về sớm so với giờ kết thúc ca cuối cùng trong ngày (chỉ xét khi không đi
        // muộn).
        if (record.getStatus() != AttendanceStatus.LATE) {
            List<StaffSchedule> shifts = getTodayShifts(staff.getStaffId(), today);
            LocalTime lastEnd = null;
            for (StaffSchedule s : shifts) {
                LocalTime end = s.getShift().getEndTime();
                if (lastEnd == null || end.isAfter(lastEnd)) {
                    lastEnd = end;
                }
            }
            if (lastEnd != null && now.toLocalTime().isBefore(lastEnd.minusMinutes(GRACE_MINUTES))) {
                record.setStatus(AttendanceStatus.EARLY_LEAVE);
            }
        }
        return attendanceRepository.save(record);
    }

    private StaffProfile requireStaff(Long userId) {
        StaffProfile staff = findStaffByUserId(userId);
        if (staff == null) {
            throw new IllegalArgumentException("Tài khoản của bạn chưa liên kết hồ sơ nhân viên.");
        }
        if (staff.getStatus() != StaffStatus.ACTIVE) {
            throw new IllegalArgumentException("Hồ sơ nhân viên không ở trạng thái đang làm việc.");
        }
        return staff;
    }

    // ===================== KIỂM TRA MẠNG WIFI / DẢI IP CHI NHÁNH
    // =====================

    private void requireValidNetwork(Branch branch, String clientIp) {
        if (!isAllowedIp(branch.getAllowedIpRange(), clientIp)) {
            throw new IllegalArgumentException("Bạn không kết nối mạng Wifi của chi nhánh \""
                    + branch.getBranchName() + "\" (IP hiện tại: " + normalizeIp(clientIp)
                    + "). Vui lòng kết nối đúng Wifi phòng khám để chấm công.");
        }
    }

    // Chi nhánh chưa cấu hình dải IP -> không giới hạn.
    // Hỗ trợ: IP đơn "192.168.1.5", ký tự đại diện "14.232.112.*", CIDR
    // "192.168.1.0/24"; phân cách bằng dấu phẩy.
    public static boolean isAllowedIp(String allowedRange, String clientIp) {
        if (allowedRange == null || allowedRange.isBlank()) {
            return true;
        }
        String ip = normalizeIp(clientIp);
        for (String raw : allowedRange.split("[,;]")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.contains("/")) {
                if (matchesCidr(entry, ip)) {
                    return true;
                }
            } else if (entry.contains("*")) {
                String regex = entry.replace(".", "\\.").replace("*", ".*");
                if (ip.matches(regex)) {
                    return true;
                }
            } else if (entry.equals(ip)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeIp(String ip) {
        if (ip == null) {
            return "";
        }
        String v = ip.trim();
        if (v.equals("::1") || v.equals("0:0:0:0:0:0:0:1")) {
            return "127.0.0.1";
        }
        if (v.startsWith("::ffff:")) {
            return v.substring(7);
        }
        return v;
    }

    // Tự động phân giải IP vật lý thật của máy (card Wi-Fi / LAN) thay vì loopback 127.0.0.1 khi demo trên localhost.
    public static String getLocalPhysicalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    public static String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        String ip = (xForwardedFor != null && !xForwardedFor.isBlank())
                ? xForwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();

        String normalized = normalizeIp(ip);
        if ("127.0.0.1".equals(normalized)) {
            String physicalIp = getLocalPhysicalIp();
            if (physicalIp != null && !physicalIp.isBlank() && !"127.0.0.1".equals(physicalIp)) {
                return physicalIp;
            }
        }
        return normalized;
    }

    private static boolean matchesCidr(String cidr, String ip) {
        try {
            String[] parts = cidr.split("/");
            int prefix = Integer.parseInt(parts[1].trim());
            long network = ipv4ToLong(parts[0].trim());
            long address = ipv4ToLong(ip);
            if (prefix < 0 || prefix > 32) {
                return false;
            }
            long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (network & mask) == (address & mask);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static long ipv4ToLong(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) {
            throw new IllegalArgumentException("IPv4 không hợp lệ");
        }
        long result = 0;
        for (String part : p) {
            int n = Integer.parseInt(part);
            if (n < 0 || n > 255) {
                throw new IllegalArgumentException("IPv4 không hợp lệ");
            }
            result = (result << 8) | n;
        }
        return result;
    }

    // ===================== SCR-33.4: LỊCH SỬ & THỐNG KÊ =====================

    @Transactional(readOnly = true)
    public List<AttendanceRecord> getMyHistory(Long staffId, YearMonth month) {
        return attendanceRepository.findByStaffAndRange(staffId, month.atDay(1), month.atEndOfMonth());
    }

    @Transactional(readOnly = true)
    public List<AttendanceRecord> getBranchHistory(Long branchId, YearMonth month) {
        return attendanceRepository.findByBranchAndRange(branchId, month.atDay(1), month.atEndOfMonth());
    }

    // Số ngày công thực tế (đã check-in) trong khoảng ngày - bàn giao cho FT32 tính
    // lương (actual_work_days).
    @Transactional(readOnly = true)
    public long countActualWorkDays(Long staffId, LocalDate from, LocalDate to) {
        return attendanceRepository.findByStaffAndRange(staffId, from, to).stream()
                .filter(a -> a.getCheckInAt() != null)
                .count();
    }

    // Thống kê tổng hợp của một danh sách bản ghi.
    public Map<String, Long> summarize(List<AttendanceRecord> records) {
        long workDays = 0, onTime = 0, late = 0, early = 0, minutes = 0;
        for (AttendanceRecord a : records) {
            if (a.getCheckInAt() != null) {
                workDays++;
            }
            if (a.getStatus() == AttendanceStatus.ON_TIME) {
                onTime++;
            } else if (a.getStatus() == AttendanceStatus.LATE) {
                late++;
            } else if (a.getStatus() == AttendanceStatus.EARLY_LEAVE) {
                early++;
            }
            if (a.getWorkedMinutes() != null) {
                minutes += a.getWorkedMinutes();
            }
        }
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("workDays", workDays);
        m.put("onTime", onTime);
        m.put("late", late);
        m.put("earlyLeave", early);
        m.put("workedMinutes", minutes);
        return m;
    }

    // Tổng hợp theo từng nhân viên trong chi nhánh (bảng công tháng).
    public List<Map<String, Object>> summarizeByStaff(List<AttendanceRecord> records) {
        Map<Long, List<AttendanceRecord>> grouped = new LinkedHashMap<>();
        for (AttendanceRecord a : records) {
            grouped.computeIfAbsent(a.getStaff().getStaffId(), k -> new ArrayList<>()).add(a);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<AttendanceRecord> list : grouped.values()) {
            StaffProfile staff = list.get(0).getStaff();
            Map<String, Long> s = summarize(list);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fullName", staff.getFullName());
            row.put("staffCode", staff.getStaffCode());
            row.putAll(s);
            result.add(row);
        }
        result.sort((x, y) -> String.valueOf(x.get("fullName")).compareToIgnoreCase(String.valueOf(y.get("fullName"))));
        return result;
    }
}
