package com.invest.monitor.retry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CheckRetryRepository extends JpaRepository<CheckRetryEntry, Long> {

    List<CheckRetryEntry> findByScheduledAtLessThanEqual(LocalDateTime now);

    void deleteByIsinAndFrequency(String isin, String frequency);
}
