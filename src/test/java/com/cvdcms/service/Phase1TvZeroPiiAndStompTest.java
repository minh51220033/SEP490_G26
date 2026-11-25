package com.cvdcms.service;

import com.cvdcms.event.ClinicQueueDomainEvent;
import com.cvdcms.event.PublicQueueStompEvent;
import com.cvdcms.service.ClinicQueueRealtimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Phase1TvZeroPiiAndStompTest {

    @Test
    @DisplayName("STOMP public /topic/queue/branch/{id} chỉ phát PublicQueueStompEvent không chứa PII hay tên/ID bác sĩ")
    void publicStompBranchTopicExcludesPiiAndDoctorName() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ClinicQueueRealtimeService realtimeService = new ClinicQueueRealtimeService();
        ReflectionTestUtils.setField(realtimeService, "messagingTemplate", messagingTemplate);

        ClinicQueueDomainEvent internalEvent = new ClinicQueueDomainEvent(
                "DOCTOR_SCHEDULE_CHANGED",
                1L,
                77L,
                501L,
                601L,
                "CALLED",
                "Bệnh nhân Nguyễn Văn An (0912345678) - Bác sĩ Lê Hoàng Nam",
                LocalDateTime.of(2026, 11, 22, 9, 30)
        );

        realtimeService.onClinicQueueEvent(internalEvent);

        ArgumentCaptor<Object> publicCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/queue/branch/1"), publicCaptor.capture());

        assertInstanceOf(PublicQueueStompEvent.class, publicCaptor.getValue());
        PublicQueueStompEvent publicPayload = (PublicQueueStompEvent) publicCaptor.getValue();
        String serialized = publicPayload.toString();
        assertFalse(serialized.contains("Nguyễn Văn An"), "Public STOMP không được chứa tên bệnh nhân");
        assertFalse(serialized.contains("0912345678"), "Public STOMP không được chứa SĐT");
        assertFalse(serialized.contains("Lê Hoàng Nam"), "Public STOMP không được chứa tên bác sĩ");
        assertFalse(serialized.contains("77"), "Public STOMP không được chứa doctorId");
        assertFalse(serialized.contains("501"), "Public STOMP không được chứa visitId");

        // Kênh nội bộ của bác sĩ vẫn nhận ClinicQueueDomainEvent đầy đủ
        verify(messagingTemplate).convertAndSend(eq("/topic/queue/doctor/77"), eq(internalEvent));
    }

    @Test
    @DisplayName("Template & JS TV công khai không render patientName, dob, doctorName và có trạng thái mất kết nối")
    void tvTemplateAndJsExcludePiiAndDoctorName() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/templates/reception/queue-tv.html"));
        String js = Files.readString(Path.of("src/main/resources/static/js/reception-queue-tv.js"));

        assertFalse(html.contains("row.patientName"), "queue-tv.html không được render patientName");
        assertFalse(html.contains("row.dob"), "queue-tv.html không được render dob");
        assertFalse(html.contains("row.doctorName"), "queue-tv.html không được render doctorName");
        assertFalse(html.contains("Tên bệnh nhân"), "queue-tv.html không được có cột Tên bệnh nhân");
        assertFalse(html.contains("Bác sĩ khám"), "queue-tv.html không được có cột Bác sĩ khám");

        assertFalse(js.contains("row.patientName"), "reception-queue-tv.js không được dùng row.patientName");
        assertFalse(js.contains("row.dob"), "reception-queue-tv.js không được dùng row.dob");
        assertFalse(js.contains("row.doctorName"), "reception-queue-tv.js không được dùng row.doctorName");
        assertFalse(js.contains("number.doctor"), "reception-queue-tv.js TTS không được đọc tên bác sĩ");
        assertTrue(js.contains("MẤT KẾT NỐI"), "reception-queue-tv.js phải có cảnh báo mất kết nối");
        assertTrue(js.contains("serverNowEpochMs"), "reception-queue-tv.js phải đồng bộ giờ theo máy chủ");
    }
}
