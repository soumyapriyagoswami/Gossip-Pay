package com.demo.upimesh.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Stands in for an SMS/push notification provider (Firebase Cloud Messaging,
 * Twilio, etc). For the demo we just log it and persist a record — swapping
 * in a real provider means changing only this class.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Autowired private NotificationRepository repo;

    public void send(String vpa, String message, String packetHash) {
        log.info("NOTIFY {} : \"{}\"", vpa, message);
        repo.save(new NotificationLog(vpa, message, packetHash));
    }
}
