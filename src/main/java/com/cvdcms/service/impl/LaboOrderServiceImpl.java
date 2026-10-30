package com.cvdcms.service.impl;

import com.cvdcms.DTO.LaboOrderRequestDTO;
import com.cvdcms.DTO.LaboOrderResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.LaboOrderStatus;
import com.cvdcms.entity.enums.LaboOrderType;
import com.cvdcms.repository.*;
import com.cvdcms.service.LaboOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LaboOrderServiceImpl implements LaboOrderService {

    private final LaboOrderRepository laboOrderRepository;
    private final VisitRepository visitRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Override
    @Transactional
    public List<LaboOrderResponseDTO> getOrdersByVisit(Long visitId) {
        if (visitId == null) return Collections.emptyList();
        List<LaboOrder> list = laboOrderRepository.findByVisit_VisitIdOrderBySentDateDesc(visitId);
        if (list.isEmpty()) {
            Visit visit = visitRepository.findById(visitId).orElse(null);
            if (visit != null) {
                seedDemoOrdersForVisit(visit);
                list = laboOrderRepository.findByVisit_VisitIdOrderBySentDateDesc(visitId);
            }
        }
        return list.stream().map(LaboOrderResponseDTO::fromEntity).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<LaboOrderResponseDTO> getOrdersByPatient(Long profileId) {
        if (profileId == null) return Collections.emptyList();
        return laboOrderRepository.findByPatientProfile_ProfileIdOrderBySentDateDesc(profileId)
                .stream().map(LaboOrderResponseDTO::fromEntity).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public List<LaboOrderResponseDTO> getAllOrders(String keyword, LaboOrderStatus status, Long supplierId, Long visitId, Long profileId) {
        List<LaboOrder> list = laboOrderRepository.searchLaboOrders(keyword, status, supplierId, visitId, profileId);
        if (list.isEmpty() && (keyword == null || keyword.isBlank()) && status == null && supplierId == null) {
            seedGlobalDemoOrders();
            list = laboOrderRepository.searchLaboOrders(keyword, status, supplierId, visitId, profileId);
        }
        return list.stream().map(LaboOrderResponseDTO::fromEntity).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public LaboOrderResponseDTO getOrderById(Long laboId) {
        return laboOrderRepository.findById(laboId)
                .map(LaboOrderResponseDTO::fromEntity)
                .orElse(null);
    }

    @Override
    @Transactional
    public LaboOrderResponseDTO createOrder(LaboOrderRequestDTO dto, MultipartFile imageFile) throws IOException {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu phiếu đặt xưởng không được để trống!");
        }

        LaboOrder order = new LaboOrder();

        // 1. Visit (Optional)
        if (dto.getVisitId() != null) {
            Visit visit = visitRepository.findById(dto.getVisitId()).orElse(null);
            order.setVisit(visit);
            if (visit != null && visit.getPatientProfile() != null && dto.getProfileId() == null) {
                order.setPatientProfile(visit.getPatientProfile());
            }
            if (visit != null && visit.getBranch() != null && dto.getBranchId() == null) {
                order.setBranch(visit.getBranch());
            }
            if (visit != null && visit.getPrimaryDoctor() != null && dto.getDoctorId() == null) {
                order.setDoctor(visit.getPrimaryDoctor());
            }
        }

        // 2. Patient Profile
        if (order.getPatientProfile() == null) {
            if (dto.getProfileId() != null) {
                PatientProfile profile = patientProfileRepository.findById(dto.getProfileId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bệnh nhân có ID: " + dto.getProfileId()));
                order.setPatientProfile(profile);
            } else {
                List<PatientProfile> all = patientProfileRepository.findAll();
                if (!all.isEmpty()) {
                    order.setPatientProfile(all.get(0));
                } else {
                    throw new IllegalArgumentException("Vui lòng chọn khách hàng / bệnh nhân cho phiếu đặt xưởng!");
                }
            }
        }

        // 3. Supplier (Labo Partner)
        if (dto.getSupplierId() != null) {
            Supplier supplier = supplierRepository.findById(dto.getSupplierId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy xưởng labo có ID: " + dto.getSupplierId()));
            order.setSupplier(supplier);
        } else {
            Supplier defaultSupplier = findOrCreateDefaultSupplier();
            order.setSupplier(defaultSupplier);
        }

        // 4. Branch & Doctor
        if (order.getBranch() == null && dto.getBranchId() != null) {
            order.setBranch(branchRepository.findById(dto.getBranchId()).orElse(null));
        }
        if (order.getBranch() == null) {
            List<Branch> branches = branchRepository.findAll();
            if (!branches.isEmpty()) order.setBranch(branches.get(0));
        }

        if (order.getDoctor() == null && dto.getDoctorId() != null) {
            order.setDoctor(staffProfileRepository.findById(dto.getDoctorId()).orElse(null));
        }

        // 5. Details
        order.setMaterialType(dto.getMaterialType() != null && !dto.getMaterialType().isBlank() ? dto.getMaterialType() : "Răng sứ Zirconia DDBio");
        order.setToothPositions(dto.getToothPositions() != null ? dto.getToothPositions() : "Toàn hàm");
        order.setShadeColor(dto.getShadeColor() != null ? dto.getShadeColor() : "A2 (3M)");
        order.setOrderType(dto.getOrderType() != null ? dto.getOrderType() : LaboOrderType.NEW);

        int qty = (dto.getQuantity() != null && dto.getQuantity() > 0) ? dto.getQuantity() : 1;
        order.setQuantity(qty);

        BigDecimal price = (dto.getUnitPrice() != null) ? dto.getUnitPrice() : BigDecimal.valueOf(1200000);
        order.setUnitPrice(price);
        order.setCostToClinic(price.multiply(BigDecimal.valueOf(qty)));

        order.setSentDate(dto.getSentDate() != null ? dto.getSentDate() : LocalDate.now());
        order.setTryInDate(dto.getTryInDate());
        order.setTryInTime(dto.getTryInTime() != null ? dto.getTryInTime() : "09:00");
        order.setExpectedDate(dto.getExpectedDate() != null ? dto.getExpectedDate() : order.getSentDate().plusDays(4));
        order.setStatus(dto.getStatus() != null ? dto.getStatus() : LaboOrderStatus.SENT);
        order.setBridgeInfo(dto.getBridgeInfo());
        order.setCrownInfo(dto.getCrownInfo());
        order.setNotes(dto.getNotes());

        // 6. Handle image upload or image URL
        if (imageFile != null && !imageFile.isEmpty()) {
            String savedUrl = saveUploadedImage(imageFile);
            order.setImageUrl(savedUrl);
        } else if (dto.getImageUrl() != null && !dto.getImageUrl().isBlank()) {
            order.setImageUrl(dto.getImageUrl().trim());
        }

        LaboOrder saved = laboOrderRepository.save(order);
        log.info("Created LaboOrder: {} (id: {})", saved.getLaboCode(), saved.getLaboId());
        return LaboOrderResponseDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public LaboOrderResponseDTO updateOrder(Long laboId, LaboOrderRequestDTO dto, MultipartFile imageFile) throws IOException {
        LaboOrder order = laboOrderRepository.findById(laboId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu đặt xưởng ID: " + laboId));

        if (dto.getSupplierId() != null) {
            supplierRepository.findById(dto.getSupplierId()).ifPresent(order::setSupplier);
        }
        if (dto.getMaterialType() != null) order.setMaterialType(dto.getMaterialType());
        if (dto.getToothPositions() != null) order.setToothPositions(dto.getToothPositions());
        if (dto.getShadeColor() != null) order.setShadeColor(dto.getShadeColor());
        if (dto.getOrderType() != null) order.setOrderType(dto.getOrderType());

        if (dto.getQuantity() != null && dto.getQuantity() > 0) order.setQuantity(dto.getQuantity());
        if (dto.getUnitPrice() != null) order.setUnitPrice(dto.getUnitPrice());
        if (order.getUnitPrice() != null && order.getQuantity() != null) {
            order.setCostToClinic(order.getUnitPrice().multiply(BigDecimal.valueOf(order.getQuantity())));
        }

        if (dto.getSentDate() != null) order.setSentDate(dto.getSentDate());
        if (dto.getTryInDate() != null) order.setTryInDate(dto.getTryInDate());
        if (dto.getTryInTime() != null) order.setTryInTime(dto.getTryInTime());
        if (dto.getExpectedDate() != null) order.setExpectedDate(dto.getExpectedDate());
        if (dto.getStatus() != null) order.setStatus(dto.getStatus());
        if (dto.getBridgeInfo() != null) order.setBridgeInfo(dto.getBridgeInfo());
        if (dto.getCrownInfo() != null) order.setCrownInfo(dto.getCrownInfo());
        if (dto.getNotes() != null) order.setNotes(dto.getNotes());

        if (imageFile != null && !imageFile.isEmpty()) {
            String savedUrl = saveUploadedImage(imageFile);
            order.setImageUrl(savedUrl);
        } else if (dto.getImageUrl() != null && !dto.getImageUrl().isBlank()) {
            order.setImageUrl(dto.getImageUrl().trim());
        }

        LaboOrder saved = laboOrderRepository.save(order);
        return LaboOrderResponseDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public LaboOrderResponseDTO updateStatus(Long laboId, LaboOrderStatus status, String note) {
        LaboOrder order = laboOrderRepository.findById(laboId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu đặt xưởng ID: " + laboId));

        order.setStatus(status);
        if (status == LaboOrderStatus.DELIVERED && order.getReceivedDate() == null) {
            order.setReceivedDate(LocalDate.now());
        }
        if (note != null && !note.isBlank()) {
            String existing = order.getNotes() != null ? order.getNotes() + "\n" : "";
            order.setNotes(existing + "[Cập nhật " + LocalDate.now() + "]: " + note);
        }

        LaboOrder saved = laboOrderRepository.save(order);
        return LaboOrderResponseDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public LaboOrderResponseDTO uploadImage(Long laboId, MultipartFile file, String directUrl) throws IOException {
        LaboOrder order = laboOrderRepository.findById(laboId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu đặt xưởng ID: " + laboId));

        if (file != null && !file.isEmpty()) {
            String savedUrl = saveUploadedImage(file);
            order.setImageUrl(savedUrl);
        } else if (directUrl != null && !directUrl.isBlank()) {
            order.setImageUrl(directUrl.trim());
        }

        LaboOrder saved = laboOrderRepository.save(order);
        return LaboOrderResponseDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public void deleteOrder(Long laboId) {
        laboOrderRepository.deleteById(laboId);
        log.info("Deleted LaboOrder ID: {}", laboId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getLaboStatistics(Long visitId, Long profileId) {
        List<LaboOrder> list;
        if (visitId != null) {
            list = laboOrderRepository.findByVisit_VisitIdOrderBySentDateDesc(visitId);
        } else if (profileId != null) {
            list = laboOrderRepository.findByPatientProfile_ProfileIdOrderBySentDateDesc(profileId);
        } else {
            list = laboOrderRepository.findAll();
        }

        long total = list.size();
        long sent = list.stream().filter(o -> o.getStatus() == LaboOrderStatus.SENT).count();
        long processing = list.stream().filter(o -> o.getStatus() == LaboOrderStatus.PROCESSING).count();
        long delivered = list.stream().filter(o -> o.getStatus() == LaboOrderStatus.DELIVERED).count();
        long fitted = list.stream().filter(o -> o.getStatus() == LaboOrderStatus.FITTED_SUCCESS).count();
        long withImage = list.stream().filter(o -> o.getImageUrl() != null && !o.getImageUrl().isBlank()).count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("sent", sent);
        stats.put("processing", processing);
        stats.put("delivered", delivered);
        stats.put("fitted", fitted);
        stats.put("withImage", withImage);
        return stats;
    }

    private String saveUploadedImage(MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(uploadDir, "labo");
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        String originalFilename = file.getOriginalFilename();
        String ext = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".jpg";
        String filename = "labo_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + ext;
        Path target = uploadPath.resolve(filename);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return "/uploads/labo/" + filename;
    }

    private Supplier findOrCreateDefaultSupplier() {
        Optional<Supplier> opt = supplierRepository.findBySupplierCodeIgnoreCase("LB01");
        if (opt.isPresent()) return opt.get();

        List<Supplier> all = supplierRepository.findAll();
        if (!all.isEmpty()) return all.get(0);

        Supplier s = new Supplier();
        s.setSupplierCode("LB01");
        s.setSupplierName("Labo Nha khoa Sài Gòn");
        s.setPhoneNumber("02838001122");
        s.setAddress("Quận 5, TP. Hồ Chí Minh");
        s.setIsActive(true);
        return supplierRepository.save(s);
    }

    private void seedDemoOrdersForVisit(Visit visit) {
        if (visit == null || visit.getVisitId() == null || visit.getPatientProfile() == null) {
            return;
        }
        try {
            Supplier s1 = findOrCreateDefaultSupplier();

            Supplier s2 = supplierRepository.findBySupplierCodeIgnoreCase("LB02").orElseGet(() -> {
                Supplier supp = new Supplier();
                supp.setSupplierCode("LB02");
                supp.setSupplierName("Labo Đức Phát");
                supp.setPhoneNumber("02838003344");
                supp.setAddress("Quận Tân Bình, TP. Hồ Chí Minh");
                supp.setIsActive(true);
                return supplierRepository.save(supp);
            });

            Supplier sMedlatec = supplierRepository.findBySupplierCodeIgnoreCase("LB-XN").orElseGet(() -> {
                Supplier supp = new Supplier();
                supp.setSupplierCode("LB-XN");
                supp.setSupplierName("Trung tâm Xét nghiệm Y khoa Medlatec");
                supp.setPhoneNumber("1900565656");
                supp.setAddress("Số 42 Nghĩa Dũng, Ba Đình, Hà Nội");
                supp.setIsActive(true);
                return supplierRepository.save(supp);
            });

            String code1 = "LAB-V" + visit.getVisitId() + "-01";
            if (code1.length() > 20) code1 = code1.substring(0, 20);
            if (laboOrderRepository.findByLaboCodeIgnoreCase(code1).isEmpty()) {
                // 1. Phiếu Răng sứ Zirconia
                LaboOrder o1 = new LaboOrder();
                o1.setLaboCode(code1);
                o1.setVisit(visit);
                o1.setPatientProfile(visit.getPatientProfile());
                o1.setBranch(visit.getBranch());
                o1.setDoctor(visit.getPrimaryDoctor());
                o1.setSupplier(s1);
                o1.setOrderType(LaboOrderType.NEW);
                o1.setToothPositions("R26");
                o1.setMaterialType("Răng sứ Zirconia DDBio cao cấp");
                o1.setShadeColor("A2 (3M)");
                o1.setQuantity(1);
                o1.setUnitPrice(BigDecimal.valueOf(1500000));
                o1.setCostToClinic(BigDecimal.valueOf(1500000));
                o1.setSentDate(LocalDate.now().minusDays(3));
                o1.setTryInDate(LocalDate.now().plusDays(1));
                o1.setTryInTime("09:30");
                o1.setExpectedDate(LocalDate.now().plusDays(2));
                o1.setStatus(LaboOrderStatus.PROCESSING);
                o1.setCrownInfo("Mão đơn lẻ R26");
                o1.setNotes("Lấy dấu cao su 2 thì, đường hoàn tất bờ vai 0.5mm, khớp cắn chuẩn giải phẫu, bề mặt đánh bóng kỹ.");
                o1.setImageUrl("https://images.unsplash.com/photo-1588776814546-1ffcf47267a5?auto=format&fit=crop&w=800&q=80");
                laboOrderRepository.save(o1);
            }

            String code2 = "XN-V" + visit.getVisitId() + "-02";
            if (code2.length() > 20) code2 = code2.substring(0, 20);
            if (laboOrderRepository.findByLaboCodeIgnoreCase(code2).isEmpty()) {
                // 2. Phiếu Mẫu Máu & Giấy xét nghiệm đông máu (PT/APTT/INR)
                LaboOrder o2 = new LaboOrder();
                o2.setLaboCode(code2);
                o2.setVisit(visit);
                o2.setPatientProfile(visit.getPatientProfile());
                o2.setBranch(visit.getBranch());
                o2.setDoctor(visit.getPrimaryDoctor());
                o2.setSupplier(sMedlatec);
                o2.setOrderType(LaboOrderType.NEW);
                o2.setToothPositions("Mẫu máu ngoại vi");
                o2.setMaterialType("Xét nghiệm đông máu PT/APTT/INR & Công thức máu (CBC)");
                o2.setShadeColor("Ống nghiệm EDTA/Citrate");
                o2.setQuantity(1);
                o2.setUnitPrice(BigDecimal.valueOf(350000));
                o2.setCostToClinic(BigDecimal.valueOf(350000));
                o2.setSentDate(LocalDate.now().minusDays(1));
                o2.setTryInDate(LocalDate.now());
                o2.setTryInTime("14:00");
                o2.setExpectedDate(LocalDate.now());
                o2.setReceivedDate(LocalDate.now());
                o2.setStatus(LaboOrderStatus.DELIVERED);
                o2.setNotes("Bệnh nhân chuẩn bị cấy ghép Implant & nhổ răng khôn R38. Kiểm tra chỉ số đông máu: PT = 12.5s (bình thường), INR = 1.02. Đủ điều kiện phẫu thuật.");
                o2.setImageUrl("https://images.unsplash.com/photo-1579154204601-01588f351e67?auto=format&fit=crop&w=800&q=80");
                laboOrderRepository.save(o2);
            }

            log.info("Seeded demo labo & blood test orders for visit: {}", visit.getVisitId());
        } catch (Exception e) {
            log.warn("Could not seed demo labo orders: {}", e.getMessage());
        }
    }

    private void seedGlobalDemoOrders() {
        List<Visit> visits = visitRepository.findAll();
        if (!visits.isEmpty()) {
            seedDemoOrdersForVisit(visits.get(0));
        }
    }
}
