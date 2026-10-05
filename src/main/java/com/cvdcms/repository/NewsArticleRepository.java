package com.cvdcms.repository;

import com.cvdcms.entity.NewsArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    Optional<NewsArticle> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query("""
        SELECT n
        FROM NewsArticle n
        WHERE n.status = 'PUBLISHED'
          AND (
              LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(COALESCE(n.summary, ''))
                  LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
        """)
    Page<NewsArticle> searchPublished(
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
        SELECT n
        FROM NewsArticle n
        WHERE
            (
                LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(n.summary, ''))
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            AND (
                :status = ''
                OR n.status = :status
            )
        """)
    Page<NewsArticle> searchAdmin(
            @Param("keyword") String keyword,
            @Param("status") String status,
            Pageable pageable
    );
}