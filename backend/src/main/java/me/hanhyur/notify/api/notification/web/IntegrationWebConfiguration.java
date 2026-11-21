package me.hanhyur.notify.api.notification.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class IntegrationWebConfiguration implements WebMvcConfigurer {

    private final IntegrationClientAccess access;

    IntegrationWebConfiguration(IntegrationClientAccess access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(access).addPathPatterns(IntegrationNotificationController.PATH);
    }
}
