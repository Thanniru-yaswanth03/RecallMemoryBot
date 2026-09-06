package com.recallbot.telegram;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TelegramUpdateRepository extends JpaRepository<TelegramUpdateEntity, Long> {
}
