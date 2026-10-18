package com.ticket2h.common.config;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ticket2h.common.security.AuthUser;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/** Mô tả API (springdoc) ở /v3/api-docs. Web sinh kiểu TypeScript từ đây. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static {
        // AuthUser lấy từ JWT (AuthUserArgumentResolver), không phải tham số người gọi gửi lên
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthUser.class);
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("Ticket2h core-service").version("v1"));
    }
}
