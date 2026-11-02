package com.cvdcms.emr;

import com.cvdcms.entity.ClinicalPhoto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class EmrAttachmentStorage {
    private final Path sourceRoot;
    private final Path archiveRoot;

    public EmrAttachmentStorage(@Value("${app.upload.dir:uploads}") String uploadRoot,
                                @Value("${app.emr.private-dir:private-uploads/emr}") String privateRoot) {
        sourceRoot = Paths.get(uploadRoot).toAbsolutePath().normalize();
        archiveRoot = Paths.get(privateRoot).toAbsolutePath().normalize();
    }

    public EmrAttachment archive(EmrRecordVersion version, ClinicalPhoto photo) {
        EmrAttachment attachment = archive(version, photo.getImageUrl(), photo.getCaption());
        attachment.setSourcePhotoId(photo.getPhotoId());
        attachment.setCapturedAt(photo.getCapturedAt());
        return attachment;
    }

    public EmrAttachment archive(EmrRecordVersion version, String url, String caption) {
        String folder;
        if (url != null && url.startsWith("/uploads/clinical_photos/")) folder = "clinical_photos";
        else if (url != null && url.startsWith("/uploads/nanopix/")) folder = "nanopix";
        else if (url != null && url.startsWith("/uploads/labo/")) folder = "labo";
        else throw new IllegalStateException("Tài liệu chưa nằm trong kho tệp nội bộ: " + url);
        String filename = url.substring(("/uploads/" + folder + "/").length());
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\"))
            throw new IllegalStateException("Đường dẫn ảnh lâm sàng không hợp lệ.");
        Path folderRoot = sourceRoot.resolve(folder);
        Path source = folderRoot.resolve(filename).normalize();
        if (!source.startsWith(folderRoot) || !Files.isRegularFile(source))
            throw new IllegalStateException("Không tìm thấy tài liệu nguồn: " + filename);
        try {
            long size = Files.size(source);
            if (size == 0 || size > 20 * 1024 * 1024)
                throw new IllegalStateException("Ảnh lâm sàng rỗng hoặc vượt quá 20 MB: " + filename);
            byte[] data = Files.readAllBytes(source);
            String mime = Files.probeContentType(source);
            if (!"image/png".equals(mime) && !"image/jpeg".equals(mime) && !"image/webp".equals(mime))
                throw new IllegalStateException("Tệp đính kèm không phải ảnh: " + filename);
            Files.createDirectories(archiveRoot);
            String storageName = UUID.randomUUID() + ".bin";
            Files.write(archiveRoot.resolve(storageName), data, StandardOpenOption.CREATE_NEW);
            EmrAttachment attachment = new EmrAttachment();
            attachment.setVersion(version);
            attachment.setOriginalName(filename);
            attachment.setMimeType(mime);
            attachment.setByteSize((long) data.length);
            attachment.setSha256(hash(data));
            attachment.setStorageName(storageName);
            attachment.setCaption(caption);
            return attachment;
        } catch (IOException ex) { throw new IllegalStateException("Không thể lưu ảnh vào kho EMR.", ex); }
    }

    public byte[] read(EmrAttachment attachment) {
        try {
            Path path = archiveRoot.resolve(attachment.getStorageName()).normalize();
            if (!path.startsWith(archiveRoot)) throw new IllegalStateException("Đường dẫn tệp EMR không hợp lệ.");
            byte[] data = Files.readAllBytes(path);
            if (!hash(data).equals(attachment.getSha256())) throw new IllegalStateException("Tệp EMR đã thay đổi nội dung.");
            return data;
        } catch (IOException ex) { throw new IllegalStateException("Không thể đọc tệp EMR.", ex); }
    }

    private String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
