package com.cvdcms.entity.converter;

import com.cvdcms.entity.enums.PrescriptionStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PrescriptionStatusConverter implements AttributeConverter<PrescriptionStatus, String> {

    @Override
    public String convertToDatabaseColumn(PrescriptionStatus attribute) {
        if (attribute == null) {
            return PrescriptionStatus.PENDING.name();
        }
        return attribute.name();
    }

    @Override
    public PrescriptionStatus convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return PrescriptionStatus.PENDING;
        }
        try {
            return PrescriptionStatus.valueOf(dbData.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            if ("DISPENSED".equalsIgnoreCase(dbData.trim())) {
                return PrescriptionStatus.DISPENSED;
            }
            return PrescriptionStatus.PENDING;
        }
    }
}

