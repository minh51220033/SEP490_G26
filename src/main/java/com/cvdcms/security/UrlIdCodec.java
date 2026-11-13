package com.cvdcms.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Codec ma hoa ID ban ghi database thanh token URL an toan (Opaque Resource Identifier),
 * ngan lo khoa chinh tuan tu va chan doan ID (ID Enumeration).
 */
@Component("idCodec")
public class UrlIdCodec {

    private static final String DOMAIN_VISIT = "CV_VISIT";
    private static final String DOMAIN_PATIENT = "CV_PATIE";
    private static final String DOMAIN_EMR = "CV_EMR__";

    private static final String PREFIX_VISIT = "vid_";
    private static final String PREFIX_PATIENT = "pid_";
    private static final String PREFIX_EMR = "emr_";

    private static final int TOKEN_BODY_LENGTH = 22;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec secretKeySpec;

    public UrlIdCodec() {
        this("cvdcms-opaque-url-codec-key-2026");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public UrlIdCodec(@Value("${app.security.url-id-secret:cvdcms-opaque-url-codec-key-2026}") String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = Arrays.copyOf(digest.digest(secret.getBytes(StandardCharsets.UTF_8)), 16);
            this.secretKeySpec = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("Khong the khoi tao UrlIdCodec", ex);
        }
    }

    public String encodeVisit(Long visitId) {
        return encode(PREFIX_VISIT, DOMAIN_VISIT, visitId);
    }

    public Long decodeVisitOrThrow(String ref) {
        return decodeOrThrow(PREFIX_VISIT, DOMAIN_VISIT, ref);
    }

    public Long decodeVisitOrNull(String ref) {
        return decodeOrNull(PREFIX_VISIT, DOMAIN_VISIT, ref);
    }

    public String encodePatient(Long patientId) {
        return encode(PREFIX_PATIENT, DOMAIN_PATIENT, patientId);
    }

    public Long decodePatientOrThrow(String ref) {
        return decodeOrThrow(PREFIX_PATIENT, DOMAIN_PATIENT, ref);
    }

    public Long decodePatientOrNull(String ref) {
        return decodeOrNull(PREFIX_PATIENT, DOMAIN_PATIENT, ref);
    }

    public String encodeEmr(Long emrId) {
        return encode(PREFIX_EMR, DOMAIN_EMR, emrId);
    }

    public Long decodeEmrOrThrow(String ref) {
        return decodeOrThrow(PREFIX_EMR, DOMAIN_EMR, ref);
    }

    public Long decodeEmrOrNull(String ref) {
        return decodeOrNull(PREFIX_EMR, DOMAIN_EMR, ref);
    }

    private String encode(String prefix, String domainTag8Bytes, Long id) {
        if (id == null || id <= 0) {
            return "";
        }
        try {
            byte[] tag = domainTag8Bytes.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer plain = ByteBuffer.allocate(16);
            plain.put(tag, 0, 8);
            plain.putLong(id);

            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec);
            byte[] encrypted = cipher.doFinal(plain.array());
            return prefix + URL_ENCODER.encodeToString(encrypted);
        } catch (Exception ex) {
            throw new IllegalStateException("Khong the ma hoa dinh danh URL", ex);
        }
    }

    private Long decodeOrThrow(String prefix, String domainTag8Bytes, String ref) {
        Long id = decodeOrNull(prefix, domainTag8Bytes, ref);
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Đường dẫn không hợp lệ hoặc đã hết hạn.");
        }
        return id;
    }

    private Long decodeOrNull(String prefix, String domainTag8Bytes, String ref) {
        if (ref == null || !ref.startsWith(prefix)) {
            return null;
        }
        String body = ref.substring(prefix.length());
        if (body.length() != TOKEN_BODY_LENGTH) {
            return null;
        }
        try {
            byte[] encrypted = URL_DECODER.decode(body);
            if (encrypted.length != 16) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec);
            byte[] plain = cipher.doFinal(encrypted);
            byte[] expectedTag = domainTag8Bytes.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 8; i++) {
                if (plain[i] != expectedTag[i]) {
                    return null;
                }
            }
            long decodedId = ByteBuffer.wrap(plain, 8, 8).getLong();
            return decodedId > 0 ? decodedId : null;
        } catch (Exception ex) {
            return null;
        }
    }
}

