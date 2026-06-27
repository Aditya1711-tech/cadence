package com.cadence.mail;

/** Sends transactional email (invites, password reset). See {@link MailConfig}. */
public interface Mailer {
    void send(String to, String subject, String body);
}
