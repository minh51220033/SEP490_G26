package com.cvdcms.DTO;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccountCreateDTO {

    private String username;

    private String password;

    private String confirmPassword;

    private String email;

    private String phoneNumber;

    private String fullName;

    private Integer roleId;
}