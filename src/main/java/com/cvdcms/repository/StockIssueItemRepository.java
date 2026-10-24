package com.cvdcms.repository;

import com.cvdcms.entity.StockIssueItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockIssueItemRepository extends JpaRepository<StockIssueItem, Long> {
}
