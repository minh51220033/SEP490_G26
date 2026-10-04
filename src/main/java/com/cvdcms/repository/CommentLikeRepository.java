package com.cvdcms.repository;

import com.cvdcms.entity.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CommentLikeRepository
        extends JpaRepository<CommentLike, Long> {

    boolean existsByComment_CommentIdAndUser_UserId(
            Long commentId,
            Long userId
    );

    Optional<CommentLike>
    findByComment_CommentIdAndUser_UserId(
            Long commentId,
            Long userId
    );

    long countByComment_CommentId(
            Long commentId
    );
}