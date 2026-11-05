package com.cvdcms.repository;

import com.cvdcms.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    Page<Comment> findByNews_NewsIdAndParentCommentIsNullAndStatus(Long newsId, String status, Pageable pageable);

    Page<Comment> findByNews_NewsIdAndParentCommentIsNull(Long newsId, Pageable pageable);

    List<Comment> findByParentComment_CommentIdAndStatusOrderByCreatedAtAsc(Long parentCommentId, String status);

    List<Comment> findByParentComment_CommentIdInOrderByCreatedAtAsc(List<Long> parentCommentIds);

    @Modifying
    @Query("DELETE FROM Comment c WHERE c.news.newsId = :newsId")
    void deleteByNews_NewsId(@Param("newsId") Long newsId);

    // Tìm kiếm bình luận trang quản trị kiểm duyệt (FT39 - SCR-39.1)
    // Lưu ý: c.content là @Lob nên không dùng LOWER() để tránh lỗi Hibernate CLOB FunctionArgumentException
    @Query(
        value = """
            SELECT c FROM Comment c
            JOIN c.news n
            JOIN c.user u
            LEFT JOIN c.parentComment p
            WHERE (
                :keyword = ''
                OR c.content LIKE CONCAT('%', :keyword, '%')
                OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            AND (:status = '' OR c.status = :status)
            AND (:newsId IS NULL OR n.newsId = :newsId)
        """,
        countQuery = """
            SELECT count(c) FROM Comment c
            JOIN c.news n
            JOIN c.user u
            WHERE (
                :keyword = ''
                OR c.content LIKE CONCAT('%', :keyword, '%')
                OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            AND (:status = '' OR c.status = :status)
            AND (:newsId IS NULL OR n.newsId = :newsId)
        """
    )
    Page<Comment> searchCommentsAdmin(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("newsId") Long newsId,
            Pageable pageable
    );

    long countByStatus(String status);
}
