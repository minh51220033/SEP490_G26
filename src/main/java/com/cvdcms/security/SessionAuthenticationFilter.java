package com.cvdcms.security;

import com.cvdcms.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Filter đồng bộ thông tin người dùng từ HttpSession (loggedInUser) vào Spring SecurityContext
 * để các bộ lọc ủy quyền (authorization) của Spring Security hoạt động chính xác.
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            Object userObj = session.getAttribute("loggedInUser");
            if (userObj instanceof User user) {
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                Object roleNamesObj = session.getAttribute("roleNames");
                if (roleNamesObj instanceof Collection<?> roleList) {
                    for (Object r : roleList) {
                        if (r != null) {
                            String roleStr = r.toString();
                            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleStr));
                            authorities.add(new SimpleGrantedAuthority(roleStr));
                        }
                    }
                }
                if (authorities.isEmpty()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                }
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(user.getUsername(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        filterChain.doFilter(request, response);
    }
}

