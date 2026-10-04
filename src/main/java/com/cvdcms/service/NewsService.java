package com.cvdcms.service;

import com.cvdcms.entity.NewsArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NewsService {

    Page<NewsArticle> searchPublished(
            String keyword,
            Pageable pageable
    );

    Page<NewsArticle> searchAdmin(
            String keyword,
            String status,
            Pageable pageable
    );

    NewsArticle findById(Long id);

    NewsArticle findBySlug(String slug);

    NewsArticle create(
            NewsArticle news
    );

    NewsArticle update(
            NewsArticle news
    );

    void publish(Long id);

    void archive(Long id);
}