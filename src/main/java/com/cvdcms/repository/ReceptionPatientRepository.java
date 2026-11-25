package com.cvdcms.repository;

import com.cvdcms.entity.ReceptionPatientRecord;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceptionPatientRepository extends JpaRepository<ReceptionPatientRecord, Long> {}
