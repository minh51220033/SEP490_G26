with open('src/test/java/com/cvdcms/NanopixXrayWorkflowUnitTest.java', 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Add mocks
target_mocks = """    @Mock private UserRepository userRepository;
    @Mock private NanoPixIntegrationService nanoPixIntegrationService;"""

rep_mocks = """    @Mock private UserRepository userRepository;
    @Mock private NanoPixIntegrationService nanoPixIntegrationService;
    @Mock private BranchRepository branchRepository;
    @Mock private StaffBranchAssignmentRepository staffBranchAssignmentRepository;"""

# 2. Update service instantiation
target_inst = """        xrayService = new NanopixXrayServiceImpl(
                nanopixRepository,
                patientProfileRepository,
                visitRepository,
                staffProfileRepository,
                paraclinicalOrderItemRepository,
                attachmentRepository,
                userRepository,
                nanoPixIntegrationService
        );"""

rep_inst = """        xrayService = new NanopixXrayServiceImpl(
                nanopixRepository,
                patientProfileRepository,
                visitRepository,
                staffProfileRepository,
                paraclinicalOrderItemRepository,
                attachmentRepository,
                userRepository,
                nanoPixIntegrationService,
                branchRepository,
                staffBranchAssignmentRepository
        );"""

# 3. Add branch to doctorUser and visit1
target_setup = """        // Visit
        visit1 = new Visit();
        visit1.setVisitId(500L);
        visit1.setVisitCode("KHAM-20261025-01");
        visit1.setPatientProfile(patient1);
        visit1.setStatus(VisitStatus.WAITING_PARACLINICAL);"""

rep_setup = """        Branch branch1 = new Branch();
        branch1.setBranchId(1L);
        branch1.setBranchName("Cơ sở Cầu Giấy");

        doctorUser.setBranch(branch1);

        // Visit
        visit1 = new Visit();
        visit1.setVisitId(500L);
        visit1.setVisitCode("KHAM-20261025-01");
        visit1.setPatientProfile(patient1);
        visit1.setBranch(branch1);
        visit1.setStatus(VisitStatus.WAITING_PARACLINICAL);"""

text_norm = text.replace("\r\n", "\n")
text_norm = text_norm.replace(target_mocks.replace("\r\n", "\n"), rep_mocks)
text_norm = text_norm.replace(target_inst.replace("\r\n", "\n"), rep_inst)
text_norm = text_norm.replace(target_setup.replace("\r\n", "\n"), rep_setup)

# 4. Add Case 10: Branch isolation check
new_test = """
    @Test
    @DisplayName("Case 10: Giới hạn cơ sở - Bác sĩ cơ sở 1 không thể đối chiếu ca khám thuộc cơ sở 2 (Chặn 403 FORBIDDEN)")
    void doctorFromBranch1_cannotReconcileVisitFromBranch2_throwsForbidden() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(doctorUser));
        when(nanopixRepository.findById(1L)).thenReturn(Optional.of(sampleSyncLog));
        when(patientProfileRepository.findById(200L)).thenReturn(Optional.of(patient1));

        Branch branch2 = new Branch();
        branch2.setBranchId(2L);
        branch2.setBranchName("Cơ sở Đống Đa");

        Visit visitBranch2 = new Visit();
        visitBranch2.setVisitId(502L);
        visitBranch2.setVisitCode("KHAM-BRANCH2-01");
        visitBranch2.setPatientProfile(patient1);
        visitBranch2.setBranch(branch2);
        visitBranch2.setStatus(VisitStatus.WAITING_PARACLINICAL);

        when(visitRepository.findById(502L)).thenReturn(Optional.of(visitBranch2));

        NanopixReconciliationRequestDTO dto = NanopixReconciliationRequestDTO.builder()
                .profileId(200L)
                .visitId(502L)
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                xrayService.reconcileXray(1L, dto, 10L)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("cơ sở khác"));
    }
"""

if "Case 10" not in text_norm:
    # insert before main
    main_idx = text_norm.find("    public static void main")
    if main_idx != -1:
        text_norm = text_norm[:main_idx] + new_test + "\n" + text_norm[main_idx:]

with open('src/test/java/com/cvdcms/NanopixXrayWorkflowUnitTest.java', 'w', encoding='utf-8') as f:
    f.write(text_norm)

print("SUCCESS TEST UPDATE")
