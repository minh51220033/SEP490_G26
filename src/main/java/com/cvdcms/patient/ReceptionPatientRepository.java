package com.cvdcms.patient;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceptionPatientRepository extends JpaRepository<ReceptionPatientRecord, Long> {}
