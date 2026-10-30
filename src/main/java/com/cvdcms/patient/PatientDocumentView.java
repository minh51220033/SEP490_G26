package com.cvdcms.patient;

import com.cvdcms.entity.PatientDocument;
import com.cvdcms.entity.enums.DocumentType;

import java.time.LocalDateTime;

public record PatientDocumentView(Long id, DocumentType type, String name,
                                  String fileType, LocalDateTime uploadedAt) {
    public static PatientDocumentView from(PatientDocument document) {
        return new PatientDocumentView(document.getDocumentId(), document.getDocumentType(),
                document.getDocumentName(), document.getFileType(),
                document.getUploadedAt());
    }
}
