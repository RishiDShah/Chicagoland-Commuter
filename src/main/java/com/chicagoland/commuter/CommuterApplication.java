package com.chicagoland.commuter;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.server.PWA;
import com.vaadin.flow.theme.lumo.Lumo;
import com.vaadin.flow.component.dependency.StyleSheet;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. Run this class from IntelliJ (green arrow) to start the app at http://localhost:8080.
 * The @PWA annotation makes the app installable to the home screen on iPhone and Android.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@PWA(name = "Chicagoland Commuter", shortName = "Commuter",
        description = "Plan trips across CTA, Metra, Pace, Divvy, bike lanes and driving",
        themeColor = "#FFFFFF", backgroundColor = "#FFFFFF")
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;700&display=swap")
@StyleSheet("styles.css")
public class CommuterApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(CommuterApplication.class, args);
    }
}
