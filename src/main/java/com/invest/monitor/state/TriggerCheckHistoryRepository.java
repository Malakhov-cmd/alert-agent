package com.invest.monitor.state;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TriggerCheckHistoryRepository extends JpaRepository<TriggerCheckHistory, Long> {

    List<TriggerCheckHistory> findByIsinAndConditionTextOrderByCheckedAtDesc(
            String isin, String conditionText, Pageable pageable);
}
