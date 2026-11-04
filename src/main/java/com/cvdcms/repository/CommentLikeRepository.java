package com.cvdcms.repository;

import com.cvdcms.entity.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
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

    @Query("SELECT cl.comment.commentId, COUNT(cl) FROM CommentLike cl WHERE cl.comment.commentId IN :commentIds GROUP BY cl.comment.commentId")
    List<Object[]> countLikesGroupedByCommentIds(@Param("commentIds") List<Long> commentIds);

    @Query("SELECT cl.comment.commentId FROM CommentLike cl WHERE cl.comment.commentId IN :commentIds AND cl.user.userId = :userId")
    List<Long> findLikedCommentIdsByUser(@Param("commentIds") List<Long> commentIds, @Param("userId") Long userId);
}
