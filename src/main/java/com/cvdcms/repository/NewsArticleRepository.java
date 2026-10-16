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
public interface NewsArticleRepository
        extends JpaRepository<NewsArticle, Long> {

    Optional<NewsArticle> findBySlug(String slug);

    boolean existsBySlug(String slug);

    // Tìm kiếm bài viết đã xuất bản.
    // branchId = null nghĩa là xem bài viết của tất cả chi nhánh.
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
    ORDER BY n.publishedAt DESC
    """)
    Page<NewsArticle> searchPublishedByBranch(
            @Param("keyword") String keyword,
            @Param("branchId") Long branchId,
            Pageable pageable
    );
    // Tìm kiếm bài viết trong trang quản trị.
    // Có thể lọc theo từ khóa, trạng thái và chi nhánh.
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