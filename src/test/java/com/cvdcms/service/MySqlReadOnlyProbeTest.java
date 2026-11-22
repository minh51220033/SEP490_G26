package com.cvdcms.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@EnabledIfSystemProperty(named = "mysql.readonly.probe", matches = "true")
class MySqlReadOnlyProbeTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void inspectBaseline() {
        dump("FLYWAY", "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 12");
        dump("BRANCHES", "SELECT branch_id, branch_code, branch_name, is_active, opening_time, closing_time, working_days FROM branches ORDER BY branch_id");
        dump("SERVICES", "SELECT s.service_id, s.service_code, s.service_name, s.status, s.is_bookable_online, c.is_active AS cat_active FROM services s JOIN service_categories c ON s.category_id=c.category_id WHERE s.status='ACTIVE' LIMIT 15");
        dump("USERS_ROLES", "SELECT u.user_id, u.username, u.user_type, u.branch_id, u.is_active, u.status, GROUP_CONCAT(r.role_name) AS roles FROM users u LEFT JOIN user_roles ur ON u.user_id=ur.user_id LEFT JOIN roles r ON ur.role_id=r.role_id GROUP BY u.user_id ORDER BY u.user_id LIMIT 40");
        dump("DOCTORS", "SELECT sp.staff_id, sp.staff_code, sp.full_name, sp.status, u.user_id, u.username, sba.branch_id FROM staff_profiles sp JOIN users u ON sp.user_id=u.user_id LEFT JOIN staff_branch_assignments sba ON sba.staff_id=sp.staff_id AND sba.status='ACTIVE' ORDER BY sp.staff_id LIMIT 30");
        dump("UPCOMING_SCHEDULES", "SELECT ss.schedule_id, ss.work_date, ss.branch_id, ss.staff_id, sp.staff_code, sp.full_name, ws.shift_id, ws.shift_name, ws.start_time, ws.end_time, ss.status FROM staff_schedules ss JOIN staff_profiles sp ON ss.staff_id=sp.staff_id JOIN work_shifts ws ON ss.shift_id=ws.shift_id WHERE ss.work_date >= CURDATE() ORDER BY ss.work_date, ss.staff_id LIMIT 40");
        dump("COUNTERS", "SELECT (SELECT COUNT(*) FROM users) AS usr, (SELECT COUNT(*) FROM booking_consultation_requests) AS bcr, (SELECT COUNT(*) FROM booking_consultation_logs) AS bcr_log, (SELECT COUNT(*) FROM patient_profiles) AS pat, (SELECT COUNT(*) FROM appointments) AS appt, (SELECT COUNT(*) FROM appointment_histories) AS appt_hist, (SELECT COUNT(*) FROM visits) AS visit, (SELECT COUNT(*) FROM queue_tickets) AS ticket, (SELECT COUNT(*) FROM leave_requests) AS leave_req");
        dump("LATEST_BCR", "SELECT request_id, request_code, guest_name, guest_phone, branch_id, preferred_date, preferred_session, preferred_time, status, handled_by_user_id, converted_appointment_id, created_at FROM booking_consultation_requests ORDER BY request_id DESC LIMIT 10");
        dump("LATEST_BCR_LOGS", "SELECT log_id, request_id, action, old_status, new_status, actor_user_id, target_user_id, contact_result, notes, created_at FROM booking_consultation_logs ORDER BY log_id DESC LIMIT 10");
        dump("LATEST_APPT", "SELECT appointment_id, appointment_code, branch_id, profile_id, doctor_id, appointment_date, preferred_session, expected_time, status, source FROM appointments ORDER BY appointment_id DESC LIMIT 15");
        dump("LATEST_APPT_HISTORY", "SELECT history_id, appointment_id, action, actor_user_id, old_value, new_value, reason, created_at FROM appointment_histories ORDER BY history_id DESC LIMIT 15");
        dump("LATEST_VISIT_TICKET", "SELECT v.visit_id, v.visit_code, v.branch_id, v.appointment_id, v.primary_doctor_id, v.visit_type, v.status AS visit_status, v.is_emergency, v.is_booked_and_on_time, q.ticket_id, q.display_number, q.status AS ticket_status, q.priority_score, q.priority_reason FROM visits v LEFT JOIN queue_tickets q ON q.visit_id=v.visit_id ORDER BY v.visit_id DESC LIMIT 15");
        dump("LATEST_LEAVES", "SELECT leave_id, staff_id, branch_id, shift_id, start_date, end_date, status FROM leave_requests ORDER BY leave_id DESC LIMIT 10");
    }

    private void dump(String label, String sql) {
        System.out.println("=== " + label + " ===");
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql);
            for (Map<String, Object> r : rows) {
                System.out.println(r);
            }
        } catch (Exception e) {
            System.out.println("ERR: " + e.getMessage());
        }
    }
}
