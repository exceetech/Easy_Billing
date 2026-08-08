package com.example.easy_billing

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailSender {

    // Sourced from local.properties (gitignored) via BuildConfig — see
    // app/build.gradle.kts. Never hardcode a real credential here again;
    // the previous value was committed to source and shipped inside the
    // APK, extractable by decompiling — that password must be rotated in
    // the Gmail account regardless of this change, since this fix can't
    // undo its prior exposure.
    private val SMTP_EMAIL = BuildConfig.SMTP_EMAIL
    private val SMTP_PASSWORD = BuildConfig.SMTP_PASSWORD

    fun sendEmail(subject: String, body: String) {

        if (SMTP_EMAIL.isBlank() || SMTP_PASSWORD.isBlank()) {
            throw IllegalStateException(
                "SMTP_EMAIL/SMTP_PASSWORD not set. Add them to local.properties " +
                    "(see app/build.gradle.kts for the exact keys)."
            )
        }

        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(SMTP_EMAIL, SMTP_PASSWORD)
            }
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(SMTP_EMAIL))
            setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(SMTP_EMAIL)
            )
            setSubject(subject)
            setText(body)
        }

        Transport.send(message)
    }
}