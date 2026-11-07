package com.cvdcms.emr;

import com.cvdcms.entity.User;
import com.cvdcms.entity.Branch;
import com.cvdcms.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmrCoverDocumentTest {

    @Mock
    private EmrAccess access;
    @Mock
    private EmrRecordRepository records;
    @Mock
    private EmrRecordVersionRepository versions;
    @Mock
    private EmrAuditEventRepository audits;
    @Mock
    private VisitRepository visits;
    @Mock
    private ClinicalExaminationRepository examinations;
    @Mock
    private ExaminationDiagnosisRepository diagnoses;
    @Mock
    private PerformedServiceRepository performedServices;
    @Mock
    private PrescriptionRepository prescriptions;
    @Mock
    private ClinicalPhotoRepository photos;
    @Mock
    private NanopixXraySyncLogRepository xrays;
    @Mock
    private LaboOrderRepository laboOrders;
    @Mock
    private TreatmentPlanRepository treatmentPlans;
    @Mock
    private EmrAttachmentRepository attachments;
    @Mock
    private EmrAttachmentStorage attachmentStorage;
    @Mock
    private StaffProfileRepository staffProfiles;
    @Mock
    private PatientProfileRepository patientProfiles;

    private ObjectMapper mapper;
    private EmrModuleService service;
    private EmrViewText emrViewText;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        service = new EmrModuleService(
                access, records, versions, audits, visits, examinations,
                diagnoses, performedServices, prescriptions, photos,
                xrays, laboOrders, treatmentPlans, attachments, attachmentStorage,
                staffProfiles, patientProfiles, mapper);
        emrViewText = new EmrViewText();
    }

    @Test
    @DisplayName("EDITABLE_DOCUMENT_FIELDS contains all COVER fields")
    @SuppressWarnings("unchecked")
    void testCoverFieldsDefined() throws Exception {
        Field field = EmrModuleService.class.getDeclaredField("EDITABLE_DOCUMENT_FIELDS");
        field.setAccessible(true);
        Map<String, Set<String>> fieldsMap = (Map<String, Set<String>>) field.get(null);

        Set<String> coverFields = fieldsMap.get("COVER");
        assertThat(coverFields).isNotNull();

        List<String> expectedFields = List.of(
                "archiveNo", "recordCode", "healthFacility", "branchName", "branchCode",
                "department", "transferredDepartment", "roomBed", "bed",
                "fullName", "gender", "patientCode", "dob", "age", "phoneNumber",
                "idCardNumber", "healthInsuranceNumber", "address",
                "occupation", "workplace", "ethnicity", "nationality",
                "guardianName", "guardianPhone", "guardianRelationship",
                "referralSource", "referralDiagnosis", "admissionDiagnosis",
                "dischargeDiagnosis", "treatmentOutcome",
                "admissionAt", "dischargeAt",
                "doctorName", "facilityDirector"
        );

        for (String expected : expectedFields) {
            assertThat(coverFields).as("Field '%s' should be defined in EDITABLE_DOCUMENT_FIELDS for COVER", expected)
                    .contains(expected);
        }
    }

    @Test
    @DisplayName("EmrViewText effective resolution order: cover -> fallback -> default")
    void testEmrViewTextEffectiveResolution() {
        Map<String, String> cover = new HashMap<>();
        Map<String, String> dental = new HashMap<>();

        // Case 1: Nothing in cover or dental -> fallback default
        String val1 = emrViewText.effective(cover, "fullName", emrViewText.effective(dental, "fullName", "Nguyen Van A"));
        assertThat(val1).isEqualTo("Nguyen Van A");

        // Case 2: Dental has override -> resolves dental
        dental.put("fullName", "Dental Name");
        String val2 = emrViewText.effective(cover, "fullName", emrViewText.effective(dental, "fullName", "Nguyen Van A"));
        assertThat(val2).isEqualTo("Dental Name");

        // Case 3: Cover has override -> takes priority over dental
        cover.put("fullName", "Cover Override Name");
        String val3 = emrViewText.effective(cover, "fullName", emrViewText.effective(dental, "fullName", "Nguyen Van A"));
        assertThat(val3).isEqualTo("Cover Override Name");
    }

    @Test
    @DisplayName("updateDocument saves all COVER fields into manual JSON")
    void testUpdateDocumentCover() {
        HttpSession session = new MockHttpSession();
        User mockUser = new User();
        mockUser.setUserId(1L);
        mockUser.setUsername("doctor1");
        EmrAccess.Scope scope = new EmrAccess.Scope(mockUser, true, true, Set.of(1L));
        when(access.require(session)).thenReturn(scope);

        EmrRecord record = new EmrRecord();
        record.setRecordId(3L);
        record.setCurrentVersionNumber(1);
        record.setStatus(EmrRecordStatus.DRAFT);
        Branch branch = new Branch();
        branch.setBranchId(1L);
        record.setBranch(branch);

        EmrRecordVersion version = new EmrRecordVersion();
        version.setVersionId(10L);
        version.setVersionNumber(1);
        version.setStatus(EmrVersionStatus.DRAFT);

        EmrDocument coverDoc = new EmrDocument();
        coverDoc.setDocumentCode("COVER");
        coverDoc.setTitle("Bìa hồ sơ bệnh án");
        coverDoc.setContentJson("{\"manual\":{}}");
        version.getDocuments().add(coverDoc);
        record.getVersions().add(version);

        when(records.findDetailsById(3L)).thenReturn(Optional.of(record));
        when(versions.findByRecord_RecordIdAndVersionNumber(3L, 1)).thenReturn(Optional.of(version));

        Map<String, String> submitted = new HashMap<>();
        submitted.put("archiveNo", "LT-2026-001");
        submitted.put("fullName", "NGUYEN VAN B");
        submitted.put("gender", "Nam");
        submitted.put("dob", "15/05/1990");
        submitted.put("age", "36");
        submitted.put("department", "Khoa Răng Hàm Mặt");
        submitted.put("admissionDiagnosis", "K04.0 Viêm tủy cấp");
        submitted.put("dischargeDiagnosis", "K04.0 Đã điều trị tủy");
        submitted.put("treatmentOutcome", "Khỏi");
        submitted.put("doctorName", "BS. Nguyen Van Doc");

        service.updateDocument(session, 3L, "COVER", submitted);

        assertThat(coverDoc.getContentJson()).contains("LT-2026-001");
        assertThat(coverDoc.getContentJson()).contains("NGUYEN VAN B");
        assertThat(coverDoc.getContentJson()).contains("Khoa Răng Hàm Mặt");
        assertThat(coverDoc.getContentJson()).contains("K04.0 Viêm tủy cấp");
        assertThat(coverDoc.getDocumentHash()).isNotEmpty();
        assertThat(version.getContentHash()).isNull();
        assertThat(version.getStatus()).isEqualTo(EmrVersionStatus.DRAFT);
    }
}
