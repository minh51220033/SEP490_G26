package com.cvdcms.reception;

import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.ClinicQueueCoordinatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:queue_chair_alloc_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;NON_KEYWORDS=MONTH,YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueueCallTimeChairAllocationIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired private ClinicQueueCoordinatorService coordinator;
    @Autowired private QueueWorkflowService queueWorkflowService;
    @Autowired private BranchRepository branchRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private DentalChairRepository chairRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffProfileRepository staffProfileRepository;
    @Autowired private StaffBranchAssignmentRepository branchAssignmentRepository;
    @Autowired private PatientProfileRepository patientProfileRepository;
    @Autowired private VisitRepository visitRepository;
    @Autowired private ClinicalQueueTicketRepository ticketRepository;

    private Branch branch;
    private Room room;
    private DentalChair chair1;
    private User adminUser;
    private User doc1User;
    private StaffProfile doc1;
    private User doc2User;
    private StaffProfile doc2;
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(0);

    @BeforeEach
    void setUp() {
        int id = SEQ.incrementAndGet();

        branch = new Branch();
        branch.setBranchCode("CS-QCA-" + id);
        branch.setBranchName("Chi nhanh Call-Time Chair " + id);
        branch.setAddress("Ha Noi");
        branch.setIsActive(true);
        branch.setResourceEngineEnabled(false);
        branch = branchRepository.saveAndFlush(branch);

        room = new Room();
        room.setBranch(branch);
        room.setRoomCode("P101-" + id);
        room.setRoomName("Phong Dieu Tri 101");
        room.setRoomType(RoomType.TREATMENT);
        room.setStatus(RoomStatus.ACTIVE);
        room = roomRepository.saveAndFlush(room);

        chair1 = new DentalChair();
        chair1.setBranch(branch);
        chair1.setRoom(room);
        chair1.setChairCode("G101A-" + id);
        chair1.setChairName("Ghe 101A");
        chair1.setStatus(ChairStatus.AVAILABLE);
        chair1 = chairRepository.saveAndFlush(chair1);

        Role adminRole = ensureRole("ADMIN");
        Role docRole = ensureRole("BAC_SI");

        adminUser = createUser("admin_qca_" + id, adminRole, UserType.STAFF);
        doc1User = createUser("doc1_qca_" + id, docRole, UserType.STAFF);
        doc1 = createDoctor("BS-QCA-1-" + id, "BS Nguyen A", doc1User, docRole);
        doc2User = createUser("doc2_qca_" + id, docRole, UserType.STAFF);
        doc2 = createDoctor("BS-QCA-2-" + id, "BS Tran B", doc2User, docRole);
    }

    private Role ensureRole(String name) {
        return roleRepository.findByRoleName(name).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(name);
            r.setIsActive(true);
            return roleRepository.saveAndFlush(r);
        });
    }

    private User createUser(String username, Role role, UserType type) {
        int uSeq = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPhoneNumber("091" + String.format("%07d", uSeq));
        u.setPassword("$2a$10$dummy");
        u.setUserType(type);
        u.setIsActive(true);
        u.setStatus("ACTIVE");
        u.setBranch(branch);
        u.setRoles(Set.of(role));
        return userRepository.saveAndFlush(u);
    }

    private StaffProfile createDoctor(String code, String name, User user, Role role) {
        StaffProfile sp = new StaffProfile();
        sp.setStaffCode(code);
        sp.setFullName(name);
        sp.setStatus(StaffStatus.ACTIVE);
        sp.setUser(user);
        sp = staffProfileRepository.saveAndFlush(sp);

        StaffBranchAssignment sba = new StaffBranchAssignment();
        sba.setStaff(sp);
        sba.setBranch(branch);
        sba.setRole(role);
        sba.setIsPrimary(true);
        sba.setStartDate(LocalDate.now(ZONE).minusDays(1));
        sba.setStatus(AssignmentStatus.ACTIVE);
        branchAssignmentRepository.saveAndFlush(sba);
        return sp;
    }

    private Visit createWaitingVisit(String patientName, StaffProfile doctor, boolean emergency, boolean onTime) {
        int pSeq = SEQ.incrementAndGet();
        PatientProfile pp = new PatientProfile();
        pp.setPatientCode("BN-QCA-" + pSeq);
        pp.setFullName(patientName);
        pp.setPhoneNumber("098" + String.format("%07d", pSeq));
        pp.setAddress("Ha Noi");
        pp.setGender("MALE");
        pp.setStatus("ACTIVE");
        pp.setPreferredBranch(branch);
        pp = patientProfileRepository.saveAndFlush(pp);

        Visit v = new Visit();
        v.setVisitCode("V-QCA-" + pSeq);
        v.setBranch(branch);
        v.setPatientProfile(pp);
        v.setPrimaryDoctor(doctor);
        v.setVisitType(emergency ? VisitType.EMERGENCY : VisitType.WALK_IN);
        v.setIsEmergency(emergency);
        v.setIsBookedAndOnTime(onTime);
        v.setStatus(VisitStatus.WAITING);
        v.setCheckInAt(LocalDateTime.now(ZONE));
        return visitRepository.saveAndFlush(v);
    }

    private MockHttpSession sessionFor(User u) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("loggedInUser", u);
        s.setAttribute("userId", u.getUserId());
        s.setAttribute("username", u.getUsername());
        s.setAttribute("currentBranchId", branch.getBranchId());
        return s;
    }

    @Test
    @DisplayName("1 & 10: Hai bác sĩ cùng tranh 1 ghế trống duy nhất — chỉ 1 bác sĩ giữ được ghế và chuyển CALLED")
    void twoDoctorsCompeteForSingleChairOnlyOneCallsAndHoldsChair() throws Exception {
        Visit v1 = createWaitingVisit("Benh Nhan Mot", doc1, false, false);
        Visit v2 = createWaitingVisit("Benh Nhan Hai", doc2, false, false);

        // Tạo ticket WAITING trực tiếp để kích hoạt cạnh tranh gọi số đồng thời
        QueueTicket t1 = saveWaitingTicket(v1, 1, "01", 50, false);
        QueueTicket t2 = saveWaitingTicket(v2, 2, "02", 50, false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Optional<QueueTicket>> c1 = () -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), doc1.getStaffId(), doc1User.getUserId());
        };
        Callable<Optional<QueueTicket>> c2 = () -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return coordinator.autoCallNextForDoctorIfIdle(branch.getBranchId(), doc2.getStaffId(), doc2User.getUserId());
        };
        Future<Optional<QueueTicket>> f1 = pool.submit(c1);
        Future<Optional<QueueTicket>> f2 = pool.submit(c2);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();

        Optional<QueueTicket> r1 = f1.get(15, TimeUnit.SECONDS);
        Optional<QueueTicket> r2 = f2.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, (r1.isPresent() ? 1 : 0) + (r2.isPresent() ? 1 : 0),
                "Chỉ đúng 1 bác sĩ được phép giành ghế trống duy nhất");

        QueueTicket reloaded1 = ticketRepository.findById(t1.getTicketId()).orElseThrow();
        QueueTicket reloaded2 = ticketRepository.findById(t2.getTicketId()).orElseThrow();
        Visit rv1 = visitRepository.findById(v1.getVisitId()).orElseThrow();
        Visit rv2 = visitRepository.findById(v2.getVisitId()).orElseThrow();

        long calledCount = (reloaded1.getStatus() == QueueStatus.CALLED ? 1 : 0)
                + (reloaded2.getStatus() == QueueStatus.CALLED ? 1 : 0);
        assertEquals(1, calledCount);
        assertNotEquals(rv1.getChairId(), rv2.getChairId(), "Hai Visit không được cùng giữ một ghế");
    }

    @Test
    @DisplayName("2 & 3 & 7 & 8: Ghế TURNOVER/bận giữ WAITING; hoàn tất turnover tự gọi; TV hiển thị đúng")
    void noChairOrTurnoverHoldsWaitingAndCompleteTurnoverAutoCallsAndUpdatesTv() {
        chair1.setStatus(ChairStatus.TURNOVER);
        chairRepository.saveAndFlush(chair1);

        Visit v = createWaitingVisit("Nguyen Van Cho Ghe", doc1, false, true);
        QueueTicket issued = coordinator.issueTicketForVisitIfEligible(v.getVisitId(), adminUser.getUserId());
        assertNotNull(issued);

        QueueTicket afterIssue = ticketRepository.findById(issued.getTicketId()).orElseThrow();
        Visit afterVisit = visitRepository.findById(v.getVisitId()).orElseThrow();
        assertEquals(QueueStatus.WAITING, afterIssue.getStatus(), "Ghế đang TURNOVER thì không được gọi CALLED");
        assertNull(afterVisit.getChairId());
        assertTrue(afterVisit.getNotes() != null && afterVisit.getNotes().contains("Chưa có phòng/ghế khả dụng"));

        // Kiểm tra TV lúc WAITING không lộ phòng/ghế giả
        QueueWorkflowService.PublicBoard tvWaiting = queueWorkflowService.publicBoard(branch.getBranchId());
        assertEquals(1, tvWaiting.queueList().size());
        assertEquals("Chưa phân công", tvWaiting.queueList().get(0).roomName());
        assertEquals("Chưa phân công", tvWaiting.queueList().get(0).chairName());

        // Hoàn tất vệ sinh ghế: TURNOVER -> AVAILABLE -> tự gọi lượt chờ
        queueWorkflowService.completeChairTurnover(sessionFor(adminUser), chair1.getChairId(), null);

        QueueTicket afterTurnover = ticketRepository.findById(issued.getTicketId()).orElseThrow();
        Visit visitCalled = visitRepository.findById(v.getVisitId()).orElseThrow();
        assertEquals(QueueStatus.CALLED, afterTurnover.getStatus());
        assertEquals(chair1.getChairId(), visitCalled.getChairId());
        assertEquals(room.getRoomId(), visitCalled.getRoomId());

        // Kiểm tra TV lúc CALLED hiển thị phòng/ghế thật
        QueueWorkflowService.PublicBoard tvCalled = queueWorkflowService.publicBoard(branch.getBranchId());
        assertEquals("Phong Dieu Tri 101", tvCalled.queueList().get(0).roomName());
        assertEquals("Ghe 101A", tvCalled.queueList().get(0).chairName());
        assertEquals("Nguyen Van Cho Ghe", tvCalled.queueList().get(0).patientFullName());
    }

    @Test
    @DisplayName("4 & 5 & 6: Vắng mặt giải phóng ghế & gọi người sau; Recall giữ/cấp lại ghế; BS đang IN_EXAM không gọi chồng")
    void absentReleasesChairRecallReallocatesAndBusyDoctorDoesNotDoubleCall() {
        Visit v1 = createWaitingVisit("BN Vang Mat", doc1, false, true);
        QueueTicket t1 = coordinator.issueTicketForVisitIfEligible(v1.getVisitId(), adminUser.getUserId());
        assertEquals(QueueStatus.CALLED, ticketRepository.findById(t1.getTicketId()).orElseThrow().getStatus());

        Visit v2 = createWaitingVisit("BN Ke Tiep", doc1, false, false);
        QueueTicket t2 = coordinator.issueTicketForVisitIfEligible(v2.getVisitId(), adminUser.getUserId());
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(t2.getTicketId()).orElseThrow().getStatus());

        // Ghi nhận vắng mặt t1 -> giải phóng ghế khỏi v1 và tự động gọi t2 chiếm chair1
        coordinator.markCalledPatientAbsent(doc1User, branch.getBranchId(), t1.getTicketId(), "Khong co mat tai sanh cho");
        Visit v1AfterAbsent = visitRepository.findById(v1.getVisitId()).orElseThrow();
        assertNull(v1AfterAbsent.getChairId());
        assertNull(v1AfterAbsent.getRoomId());
        assertEquals(QueueStatus.CALLED, ticketRepository.findById(t2.getTicketId()).orElseThrow().getStatus());
        assertEquals(chair1.getChairId(), visitRepository.findById(v2.getVisitId()).orElseThrow().getChairId());

        // Bác sĩ bấm Vào khám cho t2 -> IN_SERVICE / IN_EXAM (không gọi thêm ai khi đang khám)
        coordinator.enterExaminationForDoctor(doc1User, branch.getBranchId());
        assertEquals(QueueStatus.IN_SERVICE, ticketRepository.findById(t2.getTicketId()).orElseThrow().getStatus());
        assertEquals(ChairStatus.IN_USE, chairRepository.findById(chair1.getChairId()).orElseThrow().getStatus());

        // Recall t1 (RESUME) trong khi BS đang IN_EXAM và hết ghế -> t1 vẫn WAITING, không gọi chồng
        queueWorkflowService.transition(sessionFor(adminUser), t1.getTicketId(), QueueWorkflowService.Action.RESUME, null);
        assertEquals(QueueStatus.WAITING, ticketRepository.findById(t1.getTicketId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("9: Thứ tự waitingQueueComparator giữ nguyên (Emergency 100 > ON_TIME 75 > Walk-in 50)")
    void waitingQueueComparatorOrderPreserved() {
        Visit vWalkIn = createWaitingVisit("WalkIn", doc1, false, false);
        Visit vOnTime = createWaitingVisit("OnTime", doc1, false, true);
        Visit vEmerg = createWaitingVisit("Emerg", doc1, true, false);

        QueueTicket tWalk = saveWaitingTicket(vWalkIn, 1, "01", 50, false);
        QueueTicket tOn = saveWaitingTicket(vOnTime, 1, "P-01", 75, false);
        QueueTicket tEm = saveWaitingTicket(vEmerg, 2, "P-02", 100, true);

        List<QueueTicket> sorted = Arrays.asList(tWalk, tOn, tEm);
        sorted.sort(coordinator.waitingQueueComparator());
        assertEquals(List.of(tEm.getTicketId(), tOn.getTicketId(), tWalk.getTicketId()),
                sorted.stream().map(QueueTicket::getTicketId).toList());
    }

    private QueueTicket saveWaitingTicket(Visit visit, int num, String display, int score, boolean emergency) {
        QueueTicket t = new QueueTicket();
        t.setVisit(visit);
        t.setBranch(branch);
        t.setQueueDate(LocalDate.now(ZONE));
        t.setQueueType(QueueType.EXAM);
        t.setTicketNumber(num);
        t.setDisplayNumber(display);
        t.setStatus(QueueStatus.WAITING);
        t.setIsPriority(score >= 75);
        t.setIsEmergency(emergency);
        t.setPriorityScore(score);
        t.setIssuedAt(LocalDateTime.now(ZONE));
        return ticketRepository.saveAndFlush(t);
    }
}
