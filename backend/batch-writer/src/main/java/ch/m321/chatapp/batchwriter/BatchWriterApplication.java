package ch.m321.chatapp.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Startpunkt des batch-writer.
 * Einziger Datenbank-Schreiber der M321 Chat-App (PLANUNG.md 3.1).
 * @EnableScheduling fuer den zeitbasierten Flush-Trigger in BatchBuffer.
 */
@SpringBootApplication
@EnableScheduling
public class BatchWriterApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
