package com.cvdcms.reception;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DispatchControllerTest {

    @Mock DispatchService service;
    @Mock HttpSession session;

    DispatchController controller;
    User loggedInUser;
    StaffProfile doc1;
    StaffProfile doc2;

    @BeforeEach
    void setUp() {
        controller = new DispatchController(service);
        loggedInUser = new User();
        loggedInUser.setUserId(1L);

        doc1 = new StaffProfile(); doc1.setStaffId(10L); doc1.setFullName("BS. Nam");
        doc2 = new StaffProfile(); doc2.setStaffId(20L); doc2.setFullName("BS. Hoa");
    }

    @Test
    void boardPaginatesDataAcrossDoctorQueuesAndPreservesFilters() {
        when(session.getAttribute("loggedInUser")).thenReturn(loggedInUser);

        List<DispatchService.DoctorOption> doctors = List.of(
                new DispatchService.DoctorOption(10L, "BS. Nam", 8, false, "08:00"),
                new DispatchService.DoctorOption(20L, "BS. Hoa", 7, false, "08:00")
        );

        List<Visit> allVisits = new ArrayList<>();
        for (long i = 1; i <= 15; i++) {
            Visit v = new Visit();
            v.setVisitId(i);
            v.setVisitCode("VIS" + i);
            v.setStatus(VisitStatus.WAITING);
            if (i <= 10) {
                v.setPrimaryDoctor(doc1);
            } else if (i <= 13) {
                v.setPrimaryDoctor(doc2);
            } // i = 14, 15: unassigned (primaryDoctor = null)
            allVisits.add(v);
        }

        Branch branch = new Branch();
        branch.setBranchId(1L);
        DispatchService.Board board = new DispatchService.Board(
                1L,
                List.of(branch),
                allVisits,
                doctors,
                List.of(),       // rooms
                List.of(),       // chairs
                List.of(),       // queue
                LocalDate.now(), // day
                Map.of(),        // requests
                Map.of(),        // issued
                Map.of()         // queueInfo
        );

        when(service.board(session, 1L)).thenReturn(board);

        Model model = new ConcurrentModel();

        // Query page 1 (size = 10, total = 15 -> page 1 should contain items 11..15)
        String view = controller.board(1L, null, null, null, "ALL", 1, 10, session, model);

        assertEquals("reception/dispatch", view);
        assertEquals(15L, model.getAttribute("totalItems"));
        assertEquals(1, model.getAttribute("currentPage"));
        assertEquals(10, model.getAttribute("pageSize"));
        assertEquals(2, model.getAttribute("totalPages"));
        assertEquals(11L, model.getAttribute("fromItem"));
        assertEquals(15L, model.getAttribute("toItem"));

        // Items on page 1 should be 5 items (indices 10 to 14: visits 11, 12, 13 for doc2, and 14, 15 unassigned)
        @SuppressWarnings("unchecked")
        List<Visit> pagedItems = (List<Visit>) model.getAttribute("items");
        assertEquals(5, pagedItems.size());

        @SuppressWarnings("unchecked")
        Map<Long, List<Visit>> doctorVisitMap = (Map<Long, List<Visit>>) model.getAttribute("doctorVisitMap");
        assertEquals(0, doctorVisitMap.get(10L).size(), "BS Nam không có lượt khám nào trên trang 2");
        assertEquals(3, doctorVisitMap.get(20L).size(), "BS Hoa có 3 lượt khám trên trang 2");

        @SuppressWarnings("unchecked")
        List<Visit> unassignedItems = (List<Visit>) model.getAttribute("unassignedItems");
        assertEquals(2, unassignedItems.size(), "Có 2 lượt khám chưa phân BS trên trang 2");
    }
}

