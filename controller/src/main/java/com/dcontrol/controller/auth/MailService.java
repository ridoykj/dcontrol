package com.dcontrol.controller.auth;

import com.dcontrol.controller.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

/** Plain-text account emails, sent only after the triggering transaction commits. */
@Service
public class MailService {

    public record OutgoingMail(String to, String subject, String body) {
    }

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final ApplicationEventPublisher events;
    private final AppProperties properties;

    public MailService(JavaMailSender mailSender, ApplicationEventPublisher events, AppProperties properties) {
        this.mailSender = mailSender;
        this.events = events;
        this.properties = properties;
    }

    public void queueVerification(String to, String displayName, String token) {
        String link = link("/verify-email", token);
        events.publishEvent(new OutgoingMail(to, "Verify your dcontrol email", """
                Hi %s,

                Confirm your email address to finish setting up dcontrol:

                %s

                The link is valid for %d hours. If you didn't create an account, ignore this email.
                """.formatted(displayName, link, properties.jwt().emailVerificationTtl().toHours())));
    }

    public void queuePasswordReset(String to, String displayName, String token) {
        String link = link("/reset-password", token);
        events.publishEvent(new OutgoingMail(to, "Reset your dcontrol password", """
                Hi %s,

                Someone asked to reset the password of your dcontrol account. Choose a new one here:

                %s

                The link is valid for %d minutes and works once. If it wasn't you, ignore this email;
                your password stays the same.
                """.formatted(displayName, link, properties.jwt().passwordResetTtl().toMinutes())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void send(OutgoingMail mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mail().from());
        message.setTo(mail.to());
        message.setSubject(mail.subject());
        message.setText(mail.body());
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.error("Could not send '{}' email: {}", mail.subject(), e.getMessage());
        }
    }

    private String link(String path, String token) {
        return UriComponentsBuilder.fromUriString(properties.webBaseUrl())
                .path(path)
                .queryParam("token", token)
                .build()
                .toUriString();
    }
}
