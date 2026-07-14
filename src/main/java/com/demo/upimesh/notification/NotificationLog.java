package com.demo.upimesh.notification;

import jakarta.persistence.*;

import java.time.Instant;

/** Record of every notification "sent" (logged, for this demo) to a user. */
@Entity
@Table(name = "notifications")
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String vpa;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(nullable = false, length = 32)
    private String packetHash;

    @Column(nullable = false)
    private Instant sentAt;

    public NotificationLog() {}

    public NotificationLog(String vpa, String message, String packetHash) {
        this.vpa = vpa;
        this.message = message;
        this.packetHash = packetHash;
        this.sentAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getVpa() { return vpa; }
    public String getMessage() { return message; }
    public String getPacketHash() { return packetHash; }
    public Instant getSentAt() { return sentAt; }
}
