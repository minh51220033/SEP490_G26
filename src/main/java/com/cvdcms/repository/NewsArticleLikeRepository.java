package com.cvdcms.repository;

import com.cvdcms.entity.NewsArticleLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NewsArticleLikeRepository extends JpaRepository<NewsArticleLike, Long> {

    boolean existsByNews_NewsIdAndUser_UserId(Long newsId, Long userId);

    Optional<NewsArticleLike> findByNews_NewsIdAndUser_UserId(Long newsId, Long userId);

    long countByNews_NewsId(Long newsId);
}

