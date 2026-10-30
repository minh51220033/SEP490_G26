package com.cvdcms.service;

import com.cvdcms.DTO.AccountCreateDTO;
import com.cvdcms.DTO.AccountUpdateDTO;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.UserType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AccountService {

    Page<User> searchAccounts(
            String keyword,
            Integer roleId,
            Boolean active,
            Long branchId,
            UserType userType,
            Pageable pageable
    );

    User getAccountById(Long userId);

    User createAccount(AccountCreateDTO dto);

    User updateAccount(Long userId, AccountUpdateDTO dto);

    void changeRole(Long userId, Integer roleId);

    void changeRole(Long userId, Integer roleId, List<Long> permissionIds, boolean customPermissionsSubmitted);

    void toggleStatus(Long userId);
}