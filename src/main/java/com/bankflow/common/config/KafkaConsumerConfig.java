package com.bankflow.common.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, String> consumerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setAutoStartup(autoStartup);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        errorHandler.setAckAfterHandle(false);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
