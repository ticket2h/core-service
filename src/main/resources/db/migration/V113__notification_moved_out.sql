-- Microservice bước 1: email vé chuyển sang notification-service, bảng chống gửi trùng nằm trong database của
-- service đó (notification-service/src/main/resources/db/migration). Xem infra/docs/adr/011.
DROP TABLE notification_deliveries;
