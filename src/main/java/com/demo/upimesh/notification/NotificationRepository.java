package com.demo.upimesh.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findTop50ByOrderByIdDesc();
}
