package com.cvdcms.repository;

import com.cvdcms.entity.ServiceCombo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceComboRepository extends JpaRepository<ServiceCombo, Long> {

    List<ServiceCombo> findAllByOrderByComboIdAsc();

    List<ServiceCombo> findByStatusOrderByComboIdAsc(String status);

    Optional<ServiceCombo> findByComboCode(String comboCode);

    @org.springframework.data.jpa.repository.Query("""
        SELECT DISTINCT c FROM ServiceCombo c
        LEFT JOIN FETCH c.items it
        LEFT JOIN FETCH it.service
        WHERE c.comboId = :id
    """)
    Optional<ServiceCombo> findByIdWithItems(@org.springframework.data.repository.query.Param("id") Long id);
}
