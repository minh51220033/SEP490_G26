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

    List<Comment> findByParentComment_CommentIdAndStatusOrderByCreatedAtAsc(Long parentCommentId, String status);

    @Modifying
    @Query("DELETE FROM Comment c WHERE c.news.newsId = :newsId")
    void deleteByNews_NewsId(@Param("newsId") Long newsId);
}