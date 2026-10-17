package com.company.incidentdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/** Starts the Incident Desk Spring Boot web application. */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class IncidentDeskWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(IncidentDeskWebApplication.class, args);
    }
}
