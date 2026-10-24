package com.cvdcms.patient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cấu hình độc lập cho module hồ sơ lễ tân. */
@Configuration
public class PatientModuleConfiguration {

    @Bean
    public ObjectMapper patientAuditObjectMapper() {
        return new ObjectMapper();
    }
}
