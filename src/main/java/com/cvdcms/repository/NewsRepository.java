package com.cvdcms.repository;

import com.cvdcms.entity.NewsArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NewsRepository extends JpaRepository<NewsArticle, Long> {

    /**
     * Tìm tin tức đã xuất bản thuộc đúng một cơ sở.
     *
     * Không cho phép branchId null để tránh truy vấn tất cả cơ sở.
     */

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
}