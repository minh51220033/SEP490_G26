package com.cvdcms.repository;

import com.cvdcms.entity.CommentBan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CommentBanRepository
        extends JpaRepository<CommentBan, Long> {

    Optional<CommentBan>
    findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(
            Long userId
    );


    @Query("""
        SELECT b
        FROM CommentBan b
        JOIN b.user u
        WHERE b.active = true
          AND (
              LOWER(u.username)
                  LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(COALESCE(u.fullName, ''))
                  LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(u.email)
                  LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
        """)
    Page<CommentBan> searchActiveBans(
            @Param("keyword") String keyword,
            Pageable pageable
    );


    @Query("""
        SELECT b
        FROM CommentBan b
        JOIN b.user u
        WHERE (:activeOnly IS NULL OR b.active = :activeOnly)
          AND (
              LOWER(u.username)
                  LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(COALESCE(u.fullName, ''))
                  LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(u.email)
                  LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
        """)
    Page<CommentBan> searchBans(
            @Param("keyword") String keyword,
            @Param("activeOnly") Boolean activeOnly,
            Pageable pageable
    );

}