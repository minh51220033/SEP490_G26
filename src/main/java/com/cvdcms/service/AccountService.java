package com.cvdcms.service;

import com.cvdcms.DTO.AccountCreateDTO;
import com.cvdcms.DTO.AccountUpdateDTO;
import com.cvdcms.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AccountService {

    Page<User> searchAccounts(
            String keyword,
            Integer roleId,
            Boolean active,
            Pageable pageable
    );

    User getAccountById(Long userId);

    User createAccount(AccountCreateDTO dto);

    User updateAccount(Long userId, AccountUpdateDTO dto);

    void changeRole(Long userId, Integer roleId);

    void toggleStatus(Long userId);
}