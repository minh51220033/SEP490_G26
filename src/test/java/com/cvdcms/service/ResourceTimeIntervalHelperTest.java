package com.cvdcms.service;

import com.cvdcms.DTO.availability.ServiceTimeWindow;
import com.cvdcms.entity.Service;
import com.cvdcms.entity.enums.ResourceUsagePhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class ResourceTimeIntervalHelperTest {

    @Test
    @DisplayName("Tính đúng 3 pha PREP/PROCEDURE/TURNOVER và doctorRequiredInPrep/Turnover")
    void computesThreePhasesAndDoctorFlags() {
        Service svc = new Service();
        svc.setPreparationDurationMinutes(10);
        svc.setProcedureDurationMinutes(20);
        svc.setTurnoverDurationMinutes(15);
        svc.setIsRoomExclusive(true);
        svc.setRequiresChair(true);
        svc.setDoctorRequiredInPrep(false);
        svc.setDoctorRequiredInTurnover(false);

        LocalDate date = LocalDate.of(2026, 12, 1);
        ServiceTimeWindow w = ResourceTimeIntervalHelper.buildWindow(svc, date, LocalTime.of(9, 0));

        assertEquals(45, w.totalMinutes());
        assertEquals(LocalDateTime.of(date, LocalTime.of(9, 0)), w.t0());
        assertEquals(LocalDateTime.of(date, LocalTime.of(9, 10)), w.t1());
        assertEquals(LocalDateTime.of(date, LocalTime.of(9, 30)), w.t2());
        assertEquals(LocalDateTime.of(date, LocalTime.of(9, 45)), w.t3());
        assertEquals(w.t1(), w.doctorStart());
        assertEquals(w.t2(), w.doctorEnd());

        assertArrayEquals(new LocalDateTime[]{w.t0(), w.t1()}, ResourceTimeIntervalHelper.phaseInterval(w, ResourceUsagePhase.PREP));
        assertArrayEquals(new LocalDateTime[]{w.t1(), w.t2()}, ResourceTimeIntervalHelper.phaseInterval(w, ResourceUsagePhase.PROCEDURE));
        assertArrayEquals(new LocalDateTime[]{w.t2(), w.t3()}, ResourceTimeIntervalHelper.phaseInterval(w, ResourceUsagePhase.TURNOVER));
        assertArrayEquals(new LocalDateTime[]{w.t0(), w.t3()}, ResourceTimeIntervalHelper.phaseInterval(w, ResourceUsagePhase.ALL));

        svc.setDoctorRequiredInPrep(true);
        svc.setDoctorRequiredInTurnover(true);
        ServiceTimeWindow wDocAll = ResourceTimeIntervalHelper.buildWindow(svc, date, LocalTime.of(9, 0));
        assertEquals(wDocAll.t0(), wDocAll.doctorStart());
        assertEquals(wDocAll.t3(), wDocAll.doctorEnd());
    }

    @Test
    @DisplayName("Khoảng nửa mở [start, end) chạm biên không giao cắt; kiểm tra travel buffer hai chiều")
    void halfOpenBoundaryAndTravelBuffer() {
        LocalDate d = LocalDate.of(2026, 12, 1);
        LocalDateTime a0 = LocalDateTime.of(d, LocalTime.of(8, 0));
        LocalDateTime a1 = LocalDateTime.of(d, LocalTime.of(8, 30));
        LocalDateTime b0 = LocalDateTime.of(d, LocalTime.of(8, 30));
        LocalDateTime b1 = LocalDateTime.of(d, LocalTime.of(9, 0));

        assertFalse(ResourceTimeIntervalHelper.overlapsHalfOpen(a0, a1, b0, b1));
        assertFalse(ResourceTimeIntervalHelper.conflictsWithBuffer(b0, b1, a0, a1, 0, 0));
        assertTrue(ResourceTimeIntervalHelper.conflictsWithBuffer(
                LocalDateTime.of(d, LocalTime.of(8, 40)),
                LocalDateTime.of(d, LocalTime.of(9, 10)),
                a0, a1, 15, 0));
        assertFalse(ResourceTimeIntervalHelper.conflictsWithBuffer(
                LocalDateTime.of(d, LocalTime.of(8, 45)),
                LocalDateTime.of(d, LocalTime.of(9, 15)),
                a0, a1, 15, 0));
    }
}
