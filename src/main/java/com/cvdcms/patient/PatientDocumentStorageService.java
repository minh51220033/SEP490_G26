package com.cvdcms.patient;

import com.cvdcms.entity.PatientDocument;
import com.cvdcms.entity.PatientProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.DocumentType;
import com.cvdcms.repository.PatientProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PatientDocumentStorageService {
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    private final PatientDocumentRepository documents;
    private final PatientProfileRepository profiles;

    @Value("${app.patient-document-dir:private-uploads/patient-documents}")
    private String documentDir;

    public PatientDocumentStorageService(PatientDocumentRepository documents,
                                         PatientProfileRepository profiles) {
        this.documents = documents;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public List<PatientDocumentView> list(Long profileId) {
        return documents.findByPatientProfile_ProfileIdOrderByUploadedAtDesc(profileId)
                .stream().map(PatientDocumentView::from).toList();
    }

    @Transactional
    public PatientDocumentView upload(Long profileId, User uploadedBy, DocumentType type,
                                      MultipartFile file) {
        if (type != DocumentType.ID_CARD && type != DocumentType.INSURANCE_CARD)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại giấy tờ không hợp lệ.");
        if (file == null || file.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn file scan.");
        if (file.getSize() > MAX_FILE_SIZE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File scan không được vượt quá 5 MB.");

        FileKind kind = supportedFile(file.getContentType());
        PatientProfile profile = profiles.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String originalName = cleanName(file.getOriginalFilename(), type, kind.extension());

        try {
            Path directory = Paths.get(documentDir, profileId.toString())
                    .toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String storedName = UUID.randomUUID() + kind.extension();
            Path destination = directory.resolve(storedName).normalize();
            if (!destination.startsWith(directory))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên file không hợp lệ.");
            file.transferTo(destination);

            PatientDocument document = new PatientDocument();
            document.setPatientProfile(profile);
            document.setDocumentType(type);
            document.setDocumentName(originalName);
            document.setFileUrl(profileId + "/" + storedName);
            document.setFileType(kind.label());
            document.setUploadedBy(uploadedBy);
            return PatientDocumentView.from(documents.saveAndFlush(document));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể lưu file scan.", ex);
        }
    }

    @Transactional(readOnly = true)
    public PatientDocumentFile download(Long profileId, Long documentId) {
        PatientDocument document = documents
                .findByDocumentIdAndPatientProfile_ProfileId(documentId, profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            Path root = Paths.get(documentDir).toAbsolutePath().normalize();
            Path file = root.resolve(document.getFileUrl()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy file scan.");
            }
            return new PatientDocumentFile(new UrlResource(file.toUri()), document.getDocumentName(),
                    contentType(document.getFileType()));
        } catch (java.net.MalformedURLException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy file scan.", ex);
        }
    }

    private String contentType(String fileType) {
        return switch (String.valueOf(fileType).toUpperCase(Locale.ROOT)) {
            case "PDF" -> "application/pdf";
            case "JPG", "JPEG" -> "image/jpeg";
            case "PNG" -> "image/png";
            case "WEBP" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private FileKind supportedFile(String contentType) {
        return switch (String.valueOf(contentType).toLowerCase(Locale.ROOT)) {
            case "application/pdf" -> new FileKind(".pdf", "PDF");
            case "image/jpeg" -> new FileKind(".jpg", "JPG");
            case "image/png" -> new FileKind(".png", "PNG");
            case "image/webp" -> new FileKind(".webp", "WEBP");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Chỉ chấp nhận file PDF, JPG, PNG hoặc WebP.");
        };
    }

    private String cleanName(String original, DocumentType type, String extension) {
        String fallback = type == DocumentType.ID_CARD ? "scan-cccd" : "scan-bhyt";
        if (original == null || original.isBlank()) return fallback + extension;
        String name = Paths.get(original).getFileName().toString().replaceAll("[\\r\\n]", "");
        return name.length() <= 200 ? name : name.substring(0, 200);
    }

    private record FileKind(String extension, String label) {}

    public record PatientDocumentFile(Resource resource, String originalName, String contentType) {}
}
