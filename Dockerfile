# syntax=docker/dockerfile:1
# Image của core-service. Build: docker build -t core-service . (không cần cài Java hay Maven trên máy, chỉ cần Docker).
# CI (.github/workflows/ci.yml) build file này và đẩy image lên GitHub Container Registry.

# Bước 1: build jar bằng JDK, rồi tách jar thành từng lớp (thư viện, code) để Docker cache riêng từng lớp
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Tải dependency trước khi chép code: sửa code không phải tải lại dependency
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Test đã chạy ở bước riêng của CI (./mvnw verify, cần Docker cho Testcontainers), không chạy lại trong image
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/core-service-*.jar extract --layers --launcher --destination /extracted

# Bước 2: image chạy chỉ có JRE và jar đã tách, chạy bằng user thường (không phải root)
FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
EXPOSE 8080
# Cấu hình và secret đến từ biến môi trường (xem .env.example), không nằm trong image
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
