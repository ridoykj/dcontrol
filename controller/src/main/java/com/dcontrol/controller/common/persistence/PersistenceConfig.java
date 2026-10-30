package com.dcontrol.controller.common.persistence;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new EventAwareTransactionManager(emf);
    }
}
