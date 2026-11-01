package com.cvdcms.service.impl;

import com.cvdcms.DTO.BranchBankAccountDTO;
import com.cvdcms.DTO.BranchBankAccountOptionDTO;
import com.cvdcms.DTO.PaymentQrInfoDTO;
import com.cvdcms.entity.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.BranchBankAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
@Transactional
public class BranchBankAccountServiceImpl implements BranchBankAccountService {

    private final BranchBankAccountRepository branchBankAccountRepository;
    private final BranchRepository branchRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final SystemAuditLogRepository systemAuditLogRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public BranchBankAccountServiceImpl(
            BranchBankAccountRepository branchBankAccountRepository,
            BranchRepository branchRepository,
            PaymentRepository paymentRepository,
            InvoiceRepository invoiceRepository,
            UserRepository userRepository,
            SystemAuditLogRepository systemAuditLogRepository,
            ObjectMapper objectMapper
    ) {
        this.branchBankAccountRepository = branchBankAccountRepository;
        this.branchRepository = branchRepository;
        this.paymentRepository = paymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.userRepository = userRepository;
        this.systemAuditLogRepository = systemAuditLogRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchBankAccountDTO> getAccountsByBranch(Long branchId) {
        List<BranchBankAccount> list;
        if (branchId != null) {
            list = branchBankAccountRepository.findByBranchIdWithBranchOrdered(branchId);
        } else {
            list = branchBankAccountRepository.findAllWithBranchOrdered();
        }

        List<BranchBankAccountDTO> dtos = new ArrayList<>();
        for (BranchBankAccount b : list) {
            boolean hasTx = paymentRepository.existsByBankAccount_AccountId(b.getAccountId());
            dtos.add(mapToDTO(b, hasTx));
        }
        return dtos;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchBankAccountOptionDTO> getActiveAccountOptions(Long branchId) {
        if (branchId == null) {
            return Collections.emptyList();
        }
        List<BranchBankAccount> activeAccounts = branchBankAccountRepository
                .findByBranch_BranchIdAndIsActiveTrueOrderByIsDefaultDescCreatedAtDesc(branchId);

        List<BranchBankAccountOptionDTO> options = new ArrayList<>();
        for (BranchBankAccount b : activeAccounts) {
            options.add(BranchBankAccountOptionDTO.builder()
                    .accountId(b.getAccountId())
                    .branchId(b.getBranch().getBranchId())
                    .bankBin(b.getBankBin())
                    .bankName(b.getBankName())
                    .bankShortName(b.getBankShortName())
                    .accountNumber(b.getAccountNumber())
                    .accountHolderName(b.getAccountHolderName())
                    .isDefault(b.getIsDefault())
                    .build());
        }
        return options;
    }

    @Override
    @Transactional(readOnly = true)
    public BranchBankAccountDTO getAccountById(Long accountId) {
        BranchBankAccount account = branchBankAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản ngân hàng ID: " + accountId));
        boolean hasTx = paymentRepository.existsByBankAccount_AccountId(accountId);
        return mapToDTO(account, hasTx);
    }
    private void validateAccountDTO(BranchBankAccountDTO dto, Long currentAccountId) {
        if (dto == null) {
            throw new IllegalArgumentException("Dữ liệu tài khoản không được để trống.");
        }
        if (dto.getBranchId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn chi nhánh áp dụng.");
        }
        if (dto.getBankBin() == null || !dto.getBankBin().trim().matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("Mã BIN ngân hàng phải gồm đúng 6 chữ số theo chuẩn VietQR/Napas (VD: 970436).");
        }
        if (dto.getBankName() == null || dto.getBankName().trim().isEmpty() || dto.getBankName().trim().length() > 100) {
            throw new IllegalArgumentException("Tên ngân hàng không được để trống và tối đa 100 ký tự.");
        }
        if (dto.getBankShortName() != null && dto.getBankShortName().trim().length() > 50) {
            throw new IllegalArgumentException("Tên viết tắt ngân hàng tối đa 50 ký tự.");
        }
        if (dto.getAccountNumber() == null) {
            throw new IllegalArgumentException("Số tài khoản không được để trống.");
        }
        String cleanAccNum = dto.getAccountNumber().trim().replaceAll("\\s+", "");
        if (!cleanAccNum.matches("^[0-9A-Za-z]{6,25}$")) {
            throw new IllegalArgumentException("Số tài khoản ngân hàng phải từ 6 đến 25 ký tự chữ và số (không chứa khoảng trắng hoặc ký tự đặc biệt).");
        }
        if (dto.getAccountHolderName() == null || dto.getAccountHolderName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tên chủ tài khoản không được để trống.");
        }
        String cleanHolder = normalizeAccountHolderName(dto.getAccountHolderName());
        if (cleanHolder.length() < 2) {
            throw new IllegalArgumentException("Tên chủ tài khoản sau khi chuẩn hóa không hợp lệ (phải có ít nhất 2 ký tự chữ cái).");
        }
        if (dto.getNotes() != null && dto.getNotes().trim().length() > 255) {
            throw new IllegalArgumentException("Ghi chú tối đa 255 ký tự.");
        }

        // Kiểm tra chống trùng tài khoản (cùng chi nhánh + cùng BIN + cùng STK)
        List<BranchBankAccount> duplicates = branchBankAccountRepository.findDuplicates(
                dto.getBranchId(), dto.getBankBin().trim(), cleanAccNum
        );
        for (BranchBankAccount d : duplicates) {
            if (currentAccountId == null || !d.getAccountId().equals(currentAccountId)) {
                throw new IllegalArgumentException("Số tài khoản " + cleanAccNum + " của ngân hàng " + dto.getBankName().trim()
                        + " đã tồn tại trong chi nhánh này (ID tài khoản: " + d.getAccountId() + ").");
            }
        }
    }

    @Override
    public BranchBankAccountDTO createAccount(BranchBankAccountDTO dto, Long currentUserId, String ipAddress) {
        validateAccountDTO(dto, null);

        // Khóa bản ghi chi nhánh trước tiên để đảm bảo thứ tự khóa và chống race condition
        Branch branch = branchRepository.findByIdForUpdate(dto.getBranchId())
                .orElse(null);
        if (branch == null) {
            branch = branchRepository.findById(dto.getBranchId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh ID: " + dto.getBranchId()));
        }

        String cleanBin = dto.getBankBin().trim();
        String cleanAccNum = dto.getAccountNumber().trim().replaceAll("\\s+", "");
        String cleanHolder = normalizeAccountHolderName(dto.getAccountHolderName());

        boolean willBeDefault = Boolean.TRUE.equals(dto.getIsDefault());
        boolean isActive = dto.getIsActive() == null || Boolean.TRUE.equals(dto.getIsActive());

        if (willBeDefault && !isActive) {
            throw new IllegalArgumentException("Không thể đặt tài khoản ngừng hoạt động làm tài khoản mặc định.");
        }

        // Khóa các tài khoản hiện có của chi nhánh
        List<BranchBankAccount> existingAccounts = branchBankAccountRepository.findByBranchIdForUpdate(branch.getBranchId());

        // Nếu chi nhánh chưa có tài khoản nào thì tự động đặt tài khoản đầu tiên làm mặc định nếu nó active
        if (existingAccounts.isEmpty() && isActive) {
            willBeDefault = true;
        }

        if (willBeDefault) {
            for (BranchBankAccount acc : existingAccounts) {
                if (Boolean.TRUE.equals(acc.getIsDefault())) {
                    acc.setIsDefault(false);
                    branchBankAccountRepository.save(acc);
                }
            }
            // FLUSH ngay để đảm bảo UPDATE is_default = false được thực thi trước INSERT bản ghi mới
            branchBankAccountRepository.flush();
        }

        BranchBankAccount newAcc = new BranchBankAccount();
        newAcc.setBranch(branch);
        newAcc.setBankBin(cleanBin);
        newAcc.setBankName(dto.getBankName().trim());
        newAcc.setBankShortName(dto.getBankShortName() != null ? dto.getBankShortName().trim() : null);
        newAcc.setAccountNumber(cleanAccNum);
        newAcc.setAccountHolderName(cleanHolder);
        newAcc.setIsDefault(willBeDefault);
        newAcc.setIsActive(isActive);
        newAcc.setNotes(dto.getNotes() != null ? dto.getNotes().trim() : null);

        BranchBankAccount saved = branchBankAccountRepository.save(newAcc);
        branchBankAccountRepository.flush();
        BranchBankAccount effective = saved != null ? saved : newAcc;

        // Ghi Audit log
        recordAuditLog(currentUserId, branch, "CREATE_BANK_ACCOUNT", effective.getAccountId(), null, effective, ipAddress);

        return mapToDTO(effective, false);
    }

    @Override
    public BranchBankAccountDTO updateAccount(Long accountId, BranchBankAccountDTO dto, Long currentUserId, String ipAddress) {
        if (accountId == null || dto == null) {
            throw new IllegalArgumentException("Dữ liệu cập nhật không hợp lệ.");
        }

        validateAccountDTO(dto, accountId);

        // Khóa bi quan bản ghi cần sửa
        BranchBankAccount account = branchBankAccountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản ID: " + accountId));

        Branch currentBranch = account.getBranch();
        boolean hasTx = paymentRepository.existsByBankAccount_AccountId(accountId);

        String newAccNum = dto.getAccountNumber().trim().replaceAll("\\s+", "");
        String newBin = dto.getBankBin().trim();
        String newBankName = dto.getBankName().trim();
        Long newBranchId = dto.getBranchId() != null ? dto.getBranchId() : currentBranch.getBranchId();

        // NGUYÊN TẮC BẢO TOÀN LỊCH SỬ: Tài khoản đã được tham chiếu không được đổi STK, Ngân hàng, hoặc Chi nhánh
        if (hasTx) {
            boolean branchChanged = !newBranchId.equals(currentBranch.getBranchId());
            boolean accNumChanged = !newAccNum.equalsIgnoreCase(account.getAccountNumber());
            boolean binChanged = !newBin.equalsIgnoreCase(account.getBankBin());

            if (branchChanged || accNumChanged || binChanged) {
                throw new IllegalStateException("Tài khoản này đã phát sinh giao dịch thu tiền trong quá khứ. "
                        + "Không được phép thay đổi số tài khoản, ngân hàng hoặc chi nhánh nhằm bảo toàn lịch sử giao dịch. "
                        + "Vui lòng tạo tài khoản mới để thay thế và ngừng sử dụng tài khoản này.");
            }
        }

        // Khóa chi nhánh theo thứ tự tăng dần ID để tránh deadlock
        Long currentBranchId = currentBranch.getBranchId();
        if (newBranchId.equals(currentBranchId)) {
            branchRepository.findByIdForUpdate(currentBranchId);
        } else {
            Long firstLock = Math.min(currentBranchId, newBranchId);
            Long secondLock = Math.max(currentBranchId, newBranchId);
            branchRepository.findByIdForUpdate(firstLock);
            branchRepository.findByIdForUpdate(secondLock);
        }

        // Chụp snapshot trạng thái cũ để ghi audit
        Map<String, Object> oldState = captureState(account);

        boolean willBeActive = Boolean.TRUE.equals(dto.getIsActive());
        boolean willBeDefault = Boolean.TRUE.equals(dto.getIsDefault());

        // Nếu ngừng hoạt động thì không được làm mặc định
        if (!willBeActive) {
            willBeDefault = false;
        }

        // Nếu đổi chi nhánh (chỉ cho phép khi chưa có giao dịch)
        Branch targetBranch = currentBranch;
        if (!newBranchId.equals(currentBranch.getBranchId())) {
            targetBranch = branchRepository.findById(newBranchId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh mới ID: " + newBranchId));
            account.setBranch(targetBranch);
        }

        // Nếu đặt làm mặc định, khóa toàn bộ tài khoản của chi nhánh mục tiêu để hủy mặc định cũ
        if (willBeDefault) {
            List<BranchBankAccount> branchAccounts = branchBankAccountRepository.findByBranchIdForUpdate(targetBranch.getBranchId());
            for (BranchBankAccount acc : branchAccounts) {
                if (!acc.getAccountId().equals(accountId) && Boolean.TRUE.equals(acc.getIsDefault())) {
                    acc.setIsDefault(false);
                    branchBankAccountRepository.save(acc);
                }
            }
            // FLUSH trước khi bật mặc định mới để tránh va chạm uk_bba_default_branch
            branchBankAccountRepository.flush();
            account.setIsDefault(true);
        } else {
            account.setIsDefault(false);
        }

        account.setIsActive(willBeActive);
        account.setBankBin(newBin);
        account.setBankName(newBankName);
        account.setBankShortName(dto.getBankShortName() != null ? dto.getBankShortName().trim() : null);
        account.setAccountNumber(newAccNum);
        account.setAccountHolderName(normalizeAccountHolderName(dto.getAccountHolderName()));
        account.setNotes(dto.getNotes() != null ? dto.getNotes().trim() : null);

        BranchBankAccount saved = branchBankAccountRepository.save(account);
        branchBankAccountRepository.flush();
        BranchBankAccount effective = saved != null ? saved : account;

        // Ghi Audit log
        recordAuditLog(currentUserId, targetBranch, "UPDATE_BANK_ACCOUNT", effective.getAccountId(), oldState, effective, ipAddress);

        return mapToDTO(effective, hasTx);
    }

    @Override
    public void toggleAccountStatus(Long accountId, boolean active, Long currentUserId, String ipAddress) {
        BranchBankAccount account = branchBankAccountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản ID: " + accountId));

        // Khóa chi nhánh
        branchRepository.findByIdForUpdate(account.getBranch().getBranchId());

        Map<String, Object> oldState = captureState(account);

        account.setIsActive(active);
        // Nếu ngừng hoạt động thì tự động bỏ cờ mặc định
        if (!active && Boolean.TRUE.equals(account.getIsDefault())) {
            account.setIsDefault(false);
        }

        BranchBankAccount saved = branchBankAccountRepository.save(account);
        branchBankAccountRepository.flush();
        BranchBankAccount effective = saved != null ? saved : account;
        recordAuditLog(currentUserId, account.getBranch(), active ? "ACTIVATE_BANK_ACCOUNT" : "DEACTIVATE_BANK_ACCOUNT",
                effective.getAccountId(), oldState, effective, ipAddress);
    }

    @Override
    public void setDefaultAccount(Long accountId, Long currentUserId, String ipAddress) {
        BranchBankAccount targetAccount = branchBankAccountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản ID: " + accountId));

        if (!Boolean.TRUE.equals(targetAccount.getIsActive())) {
            throw new IllegalStateException("Tài khoản đang ở trạng thái ngừng sử dụng, không thể đặt làm mặc định.");
        }

        Branch branch = targetAccount.getBranch();

        // Khóa chi nhánh trước
        branchRepository.findByIdForUpdate(branch.getBranchId());

        // Khóa tất cả tài khoản của chi nhánh để chống 2 request đặt mặc định đồng thời
        List<BranchBankAccount> branchAccounts = branchBankAccountRepository.findByBranchIdForUpdate(branch.getBranchId());

        Map<String, Object> oldState = captureState(targetAccount);

        for (BranchBankAccount acc : branchAccounts) {
            if (!acc.getAccountId().equals(accountId)) {
                if (Boolean.TRUE.equals(acc.getIsDefault())) {
                    acc.setIsDefault(false);
                    branchBankAccountRepository.save(acc);
                }
            }
        }
        // FLUSH ngay để đảm bảo UPDATE is_default = false được đẩy xuống DB trước khi UPDATE cờ mặc định mới
        branchBankAccountRepository.flush();

        targetAccount.setIsDefault(true);
        BranchBankAccount saved = branchBankAccountRepository.save(targetAccount);
        branchBankAccountRepository.flush();
        BranchBankAccount effective = saved != null ? saved : targetAccount;

        recordAuditLog(currentUserId, branch, "SET_DEFAULT_BANK_ACCOUNT", effective.getAccountId(), oldState, effective, ipAddress);
    }

    @Override
    public void deleteAccount(Long accountId, Long currentUserId, String ipAddress) {
        BranchBankAccount account = branchBankAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản ID: " + accountId));

        boolean hasTx = paymentRepository.existsByBankAccount_AccountId(accountId);
        if (hasTx) {
            throw new IllegalStateException("Tài khoản ngân hàng (" + account.getAccountNumber() + " - " + account.getBankName()
                    + ") đã được tham chiếu trong các giao dịch thu tiền trước đây. "
                    + "Tuyệt đối không thể xóa cứng nhằm bảo toàn dữ liệu tài chính. Vui lòng chọn Ngừng sử dụng.");
        }

        Map<String, Object> oldState = captureState(account);
        Branch branch = account.getBranch();

        branchBankAccountRepository.delete(account);

        recordAuditLog(currentUserId, branch, "DELETE_BANK_ACCOUNT", accountId, oldState, null, ipAddress);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentQrInfoDTO getQrInfo(
            Long branchId,
            Long invoiceId,
            Long profileId,
            Long accountId,
            BigDecimal amount,
            String sessionRef
    ) {
        // 1. Kiểm tra chi nhánh và hóa đơn
        if (invoiceId != null) {
            Invoice inv = invoiceRepository.findById(invoiceId).orElse(null);
            if (inv == null) {
                return PaymentQrInfoDTO.builder()
                        .success(false)
                        .errorMessage("Hóa đơn ID " + invoiceId + " không tồn tại trong hệ thống.")
                        .build();
            }
            Long invBranchId = inv.getBranch() != null ? inv.getBranch().getBranchId() : null;
            if (invBranchId == null) {
                return PaymentQrInfoDTO.builder()
                        .success(false)
                        .errorMessage("Hóa đơn ID " + invoiceId + " chưa được liên kết với chi nhánh hợp lệ.")
                        .build();
            }
            if (branchId != null && !branchId.equals(invBranchId)) {
                return PaymentQrInfoDTO.builder()
                        .success(false)
                        .errorMessage("Chi nhánh yêu cầu (ID: " + branchId + ") mâu thuẫn với chi nhánh của hóa đơn (ID: " + invBranchId + ").")
                        .build();
            }
            branchId = invBranchId;
        }

        if (branchId == null) {
            return PaymentQrInfoDTO.builder()
                    .success(false)
                    .errorMessage("Không xác định được chi nhánh nhận tiền.")
                    .build();
        }

        Branch branch = branchRepository.findById(branchId).orElse(null);
        if (branch == null) {
            return PaymentQrInfoDTO.builder()
                    .success(false)
                    .errorMessage("Chi nhánh không tồn tại trong hệ thống.")
                    .build();
        }

        // 2. Lấy danh sách tài khoản đang hoạt động của chi nhánh này
        List<BranchBankAccount> activeAccounts = branchBankAccountRepository
                .findByBranch_BranchIdAndIsActiveTrueOrderByIsDefaultDescCreatedAtDesc(branchId);

        if (activeAccounts.isEmpty()) {
            return PaymentQrInfoDTO.builder()
                    .success(false)
                    .branchId(branchId)
                    .branchName(branch.getBranchName())
                    .errorMessage("Chi nhánh " + branch.getBranchName() + " chưa có tài khoản ngân hàng hoạt động để nhận chuyển khoản. Vui lòng liên hệ Quản trị viên (Admin) để cấu hình.")
                    .build();
        }

        // Map danh sách tài khoản khả dụng cho dropdown
        List<BranchBankAccountOptionDTO> options = new ArrayList<>();
        for (BranchBankAccount b : activeAccounts) {
            options.add(BranchBankAccountOptionDTO.builder()
                    .accountId(b.getAccountId())
                    .branchId(b.getBranch().getBranchId())
                    .bankBin(b.getBankBin())
                    .bankName(b.getBankName())
                    .bankShortName(b.getBankShortName())
                    .accountNumber(b.getAccountNumber())
                    .accountHolderName(b.getAccountHolderName())
                    .isDefault(b.getIsDefault())
                    .build());
        }

        // 3. Chọn tài khoản:
        BranchBankAccount selectedAccount = null;
        if (accountId != null) {
            for (BranchBankAccount b : activeAccounts) {
                if (b.getAccountId().equals(accountId)) {
                    selectedAccount = b;
                    break;
                }
            }
            // Nếu kế toán chỉ định accountId nhưng tài khoản đó không hợp lệ hoặc không thuộc chi nhánh -> báo lỗi rõ ràng, KHÔNG tự ý chọn tài khoản khác
            if (selectedAccount == null) {
                return PaymentQrInfoDTO.builder()
                        .success(false)
                        .branchId(branchId)
                        .branchName(branch.getBranchName())
                        .errorMessage("Tài khoản ngân hàng ID " + accountId + " không hợp lệ, không thuộc chi nhánh " + branch.getBranchName() + " hoặc đang ngừng hoạt động.")
                        .build();
            }
        } else {
            // Không truyền accountId: Tự động chọn tài khoản mặc định
            for (BranchBankAccount b : activeAccounts) {
                if (Boolean.TRUE.equals(b.getIsDefault())) {
                    selectedAccount = b;
                    break;
                }
            }
            if (selectedAccount == null) {
                selectedAccount = activeAccounts.get(0);
            }
        }

        // 4. Xác định số tiền thu lần này (mặc định lấy số tiền truyền vào hoặc kiểm tra với nợ)
        BigDecimal qrAmount = amount != null && amount.compareTo(BigDecimal.ZERO) > 0 ? amount : BigDecimal.ZERO;
        String transferContent;

        if (invoiceId != null) {
            Invoice inv = invoiceRepository.findById(invoiceId).orElse(null);
            if (inv == null) {
                return PaymentQrInfoDTO.builder()
                        .success(false)
                        .errorMessage("Hóa đơn ID " + invoiceId + " không tồn tại trong hệ thống.")
                        .build();
            }
            String invCode = inv.getInvoiceCode();
            String refCode = sessionRef != null && !sessionRef.trim().isEmpty()
                    ? sessionRef.trim().toUpperCase()
                    : "TT" + System.currentTimeMillis() % 100000;
            transferContent = invCode + " " + refCode;
        } else {
            String pRef = profileId != null ? "TU" + profileId : "TU";
            String refCode = sessionRef != null && !sessionRef.trim().isEmpty()
                    ? sessionRef.trim().toUpperCase()
                    : "REF" + System.currentTimeMillis() % 100000;
            transferContent = pRef + " " + refCode;
        }

        transferContent = sanitizeTransferContent(transferContent);

        // 5. Sinh link VietQR Quick Link theo quy chuẩn
        // Format: https://img.vietqr.io/image/<BANK_BIN>-<ACCOUNT_NO>-compact2.png?amount=<AMOUNT>&addInfo=<CONTENT>&accountName=<NAME>
        String encodedContent = URLEncoder.encode(transferContent, StandardCharsets.UTF_8);
        String encodedHolder = URLEncoder.encode(selectedAccount.getAccountHolderName(), StandardCharsets.UTF_8);
        String qrUrl = String.format(
                "https://img.vietqr.io/image/%s-%s-compact2.png?amount=%s&addInfo=%s&accountName=%s",
                selectedAccount.getBankBin(),
                selectedAccount.getAccountNumber(),
                qrAmount.toPlainString(),
                encodedContent,
                encodedHolder
        );

        return PaymentQrInfoDTO.builder()
                .success(true)
                .accountId(selectedAccount.getAccountId())
                .branchId(branchId)
                .branchName(branch.getBranchName())
                .bankBin(selectedAccount.getBankBin())
                .bankName(selectedAccount.getBankName())
                .bankShortName(selectedAccount.getBankShortName())
                .accountNumber(selectedAccount.getAccountNumber())
                .accountHolderName(selectedAccount.getAccountHolderName())
                .amount(qrAmount)
                .transferContent(transferContent)
                .qrUrl(qrUrl)
                .availableAccounts(options)
                .build();
    }

    private BranchBankAccountDTO mapToDTO(BranchBankAccount b, boolean hasTransactions) {
        return BranchBankAccountDTO.builder()
                .accountId(b.getAccountId())
                .branchId(b.getBranch() != null ? b.getBranch().getBranchId() : null)
                .branchName(b.getBranch() != null ? b.getBranch().getBranchName() : "")
                .branchCode(b.getBranch() != null ? b.getBranch().getBranchCode() : "")
                .bankBin(b.getBankBin())
                .bankName(b.getBankName())
                .bankShortName(b.getBankShortName())
                .accountNumber(b.getAccountNumber())
                .accountHolderName(b.getAccountHolderName())
                .isDefault(b.getIsDefault())
                .isActive(b.getIsActive())
                .notes(b.getNotes())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .hasTransactions(hasTransactions)
                .build();
    }

    private String normalizeAccountHolderName(String name) {
        if (name == null) return "";
        String normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String noAccents = pattern.matcher(normalized).replaceAll("").replace('đ', 'd').replace('Đ', 'D');
        return noAccents.toUpperCase().replaceAll("[^A-Z0-9 ]", "").trim();
    }

    private String sanitizeTransferContent(String content) {
        if (content == null) return "THANH TOAN";
        String normalized = Normalizer.normalize(content.trim(), Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String clean = pattern.matcher(normalized).replaceAll("").replace('đ', 'd').replace('Đ', 'D');
        clean = clean.replaceAll("[^a-zA-Z0-9 ]", "").trim();
        if (clean.length() > 50) {
            clean = clean.substring(0, 50);
        }
        return clean.toUpperCase();
    }

    private Map<String, Object> captureState(BranchBankAccount b) {
        if (b == null) return Collections.emptyMap();
        Map<String, Object> map = new HashMap<>();
        map.put("accountId", b.getAccountId());
        map.put("branchId", b.getBranch() != null ? b.getBranch().getBranchId() : null);
        map.put("bankBin", b.getBankBin());
        map.put("bankName", b.getBankName());
        map.put("accountNumber", b.getAccountNumber());
        map.put("accountHolderName", b.getAccountHolderName());
        map.put("isDefault", b.getIsDefault());
        map.put("isActive", b.getIsActive());
        return map;
    }

    private void recordAuditLog(Long userId, Branch branch, String action, Long entityId, Object oldVal, Object newVal, String ipAddress) {
        if (systemAuditLogRepository == null) return;
        try {
            SystemAuditLog log = new SystemAuditLog();
            if (userId != null) {
                userRepository.findById(userId).ifPresent(log::setUser);
            }
            log.setBranch(branch);
            log.setAction(action);
            log.setEntityTable("branch_bank_accounts");
            log.setEntityId(entityId);
            if (oldVal != null) {
                log.setOldValuesJson(objectMapper.writeValueAsString(oldVal));
            }
            if (newVal != null) {
                log.setNewValuesJson(objectMapper.writeValueAsString(newVal));
            }
            log.setIpAddress(ipAddress);
            systemAuditLogRepository.save(log);
        } catch (Exception ignored) {
            // Log audit failure must not block business transaction
        }
    }
}
