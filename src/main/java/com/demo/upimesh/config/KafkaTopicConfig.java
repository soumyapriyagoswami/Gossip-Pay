package com.demo.upimesh.config;

import com.demo.upimesh.events.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Declares the topics used by the event-driven pipeline so they exist with
 * sane partition counts even if the broker has auto-topic-creation disabled
 * (which production Kafka clusters usually do).
 *
 * 3 partitions per topic = allows up to 3 consumer instances per consumer
 * group to process in parallel while still preserving per-key (packetHash)
 * ordering, since Kafka guarantees ordering only within a partition and we
 * key every message by packetHash.
 *
 * Also explicitly declares a KafkaTemplate<String, Object> bean built from
 * the spring.kafka.producer.* properties. Spring Boot's own autoconfiguration
 * would otherwise expose KafkaTemplate<Object, Object>, which every producer
 * class here (Gateway, Settlement, Ledger) would fail to @Autowire against
 * because Java generics are invariant — declaring it here once, with the
 * exact generic signature used everywhere, avoids that mismatch.
 */
@Configuration
@Profile("event-driven")
public class KafkaTopicConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(null));
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public NewTopic packetReceivedTopic() {
        return TopicBuilder.name(KafkaTopics.PACKET_RECEIVED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic settlementCompletedTopic() {
        return TopicBuilder.name(KafkaTopics.SETTLEMENT_COMPLETED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic ledgerRecordedTopic() {
        return TopicBuilder.name(KafkaTopics.LEDGER_RECORDED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic settlementDlqTopic() {
        return TopicBuilder.name(KafkaTopics.SETTLEMENT_DLQ).partitions(1).replicas(1).build();
    }
}
