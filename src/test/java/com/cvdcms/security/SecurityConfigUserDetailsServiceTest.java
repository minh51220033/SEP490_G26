package com.cvdcms.security;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityConfigUserDetailsServiceTest {

    private UserRepository userRepository;
    private UserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        SessionAuthenticationFilter sessionAuthFilter = mock(SessionAuthenticationFilter.class);
        SecurityConfig securityConfig = new SecurityConfig(sessionAuthFilter);
        userDetailsService = securityConfig.userDetailsService(userRepository);
    }

    @Test
    @DisplayName("1. Nạp người dùng hợp lệ từ UserRepository -> Trả về UserDetails với đầy đủ Roles và Active status")
    void loadUserByUsername_success() {
        User user = new User();
        user.setUserId(1L);
        user.setUsername("doctor_minh");
        user.setPassword("$2a$10$hashed_password");
        user.setIsActive(true);

        Role role = new Role();
        role.setRoleId(2L);
        role.setRoleName("BAC_SI");
        user.setRoles(Set.of(role));

        when(userRepository.findByUsername("doctor_minh")).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername("doctor_minh");

        assertNotNull(userDetails);
        assertEquals("doctor_minh", userDetails.getUsername());
        assertEquals("$2a$10$hashed_password", userDetails.getPassword());
        assertTrue(userDetails.isEnabled());
        assertTrue(userDetails.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BAC_SI")));
        assertTrue(userDetails.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("BAC_SI")));
    }

    @Test
    @DisplayName("2. Người dùng không tồn tại -> Ném UsernameNotFoundException")
    void loadUserByUsername_notFound_throwsException() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () ->
                userDetailsService.loadUserByUsername("unknown")
        );
    }

    @Test
    @DisplayName("3. Tài khoản bị khóa (isActive = false) -> UserDetails.isEnabled() là false")
    void loadUserByUsername_inactiveUser_disabled() {
        User user = new User();
        user.setUserId(2L);
        user.setUsername("banned_user");
        user.setPassword("$2a$10$hashed_password");
        user.setIsActive(false);

        when(userRepository.findByUsername("banned_user")).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername("banned_user");

        assertNotNull(userDetails);
        assertFalse(userDetails.isEnabled());
    }
}
