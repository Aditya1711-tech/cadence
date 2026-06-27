package com.cadence.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Mailer selection (P2-A.2 §4, user decision): when SMTP_HOST is set, send over
 * SMTP for prod; otherwise log the message so the local-cloud milestone works
 * with zero email setup.
 */
@Configuration
public class MailConfig {

    @Bean
    public Mailer mailer(@Value("${spring.mail.host:}") String smtpHost,
                         @Value("${cadence.mail.from}") String from,
                         ObjectProvider<JavaMailSender> senders) {
        JavaMailSender sender = senders.getIfAvailable();
        if (smtpHost != null && !smtpHost.isBlank() && sender != null) {
            return new SmtpMailer(sender, from);
        }
        return new LogMailer();
    }

    /** Dev / no-SMTP: logs the email so reset & enroll links are retrievable. */
    static class LogMailer implements Mailer {
        private static final Logger log = LoggerFactory.getLogger(LogMailer.class);
        @Override public void send(String to, String subject, String body) {
            log.info("[DEV-MAIL] to={} subject={}\n{}", to, subject, body);
        }
    }

    /** Prod: JavaMail over SMTP (any provider; keeps AWS surface minimal vs SES). */
    static class SmtpMailer implements Mailer {
        private final JavaMailSender sender;
        private final String from;
        SmtpMailer(JavaMailSender sender, String from) { this.sender = sender; this.from = from; }
        @Override public void send(String to, String subject, String body) {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
        }
    }
}
