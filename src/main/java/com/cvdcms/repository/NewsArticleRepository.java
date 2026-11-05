package com.cvdcms.repository;

import com.cvdcms.entity.NewsArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NewsArticleRepository
        extends JpaRepository<NewsArticle, Long> {

    @EntityGraph(attributePaths = {"branch", "createdBy"})
    Optional<NewsArticle> findBySlug(String slug);

    boolean existsBySlug(String slug);

    // TÃ¬m kiáº¿m bÃ i viáº¿t Ä‘Ã£ xuáº¥t báº£n theo chi nhÃ¡nh cá»¥ thá»ƒ.
    @EntityGraph(attributePaths = {"branch", "createdBy"})
    @Query("""
    SELECT n
    FROM NewsArticle n
    WHERE n.status = 'PUBLISHED'
      AND n.branch.branchId = :branchId
      AND (
          :keyword = ''
          OR LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
          OR LOWER(COALESCE(n.summary, '')) LIKE
             LOWER(CONCAT('%', :keyword, '%'))
      )
    ORDER BY n.publishedAt DESC, n.createdAt DESC
    """)
    Page<NewsArticle> searchPublishedByBranch(
            @Param("keyword") String keyword,
            @Param("branchId") Long branchId,
            Pageable pageable
    );

    // TÃ¬m kiáº¿m bÃ i viáº¿t Ä‘Ã£ xuáº¥t báº£n cÃ´ng khai trÃªn toÃ n há»‡ thá»‘ng (branchId = null lÃ  táº¥t cáº£ chi nhÃ¡nh Ä‘ang hoáº¡t Ä‘á»™ng).
    @EntityGraph(attributePaths = {"branch", "createdBy"})
    @Query("""
    SELECT n
    FROM NewsArticle n
    LEFT JOIN n.branch b
    WHERE n.status = 'PUBLISHED'
      AND (b IS NULL OR b.isActive = true)
      AND (:branchId IS NULL OR b.branchId = :branchId)
      AND (
          :keyword = ''
          OR LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
          OR LOWER(COALESCE(n.summary, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
      )
    ORDER BY n.publishedAt DESC, n.createdAt DESC
    """)
    Page<NewsArticle> searchPublishedPublic(
            @Param("keyword") String keyword,
            @Param("branchId") Long branchId,
            Pageable pageable
    );

    // Láº¥y bÃ i viáº¿t liÃªn quan Ä‘Ã£ xuáº¥t báº£n (trá»« bÃ i viáº¿t hiá»‡n táº¡i).
    @EntityGraph(attributePaths = {"branch", "createdBy"})
    @Query("""
    SELECT n
    FROM NewsArticle n
    LEFT JOIN n.branch b
    WHERE n.status = 'PUBLISHED'
      AND (b IS NULL OR b.isActive = true)
      AND n.newsId <> :excludeNewsId
    ORDER BY n.publishedAt DESC, n.createdAt DESC
    """)
    List<NewsArticle> findRelatedPublished(
            @Param("excludeNewsId") Long excludeNewsId,
            Pageable pageable
    );

    // TÃ¬m kiáº¿m bÃ i viáº¿t trong trang quáº£n trá»‹.
    // CÃ³ thá»ƒ lá»c theo tá»« khÃ³a, tráº¡ng thÃ¡i vÃ  chi nhÃ¡nh.
    @Query("""
        SELECT n
        FROM NewsArticle n
        WHERE (
              LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(COALESCE(n.summary, ''))
                  LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
          AND (
              :status = ''
              OR n.status = :status
          )
          AND (
              :branchId IS NULL
              OR n.branch.branchId = :branchId
          )
        """)
    Page<NewsArticle> searchAdmin(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("branchId") Long branchId,
            Pageable pageable
    );

}
