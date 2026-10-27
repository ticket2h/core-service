package com.ticket2h.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Container khai báo là bean có {@code @ServiceConnection}: Spring tự cấu hình URL, user, password.
 * Container được dùng lại giữa các class test có cùng cấu hình context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        // Bản native (GraalVM) của image apache/kafka: khởi động nhanh hơn nhiều, đủ cho test
        return new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.3.1"));
    }

    /** Không có module Testcontainers riêng cho Redis: {@code GenericContainer}, Spring nhận ra theo tên image. */
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(IntegrationTest.START);
    }
}
