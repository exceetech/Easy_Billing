package com.example.easy_billing

import android.content.Context.MODE_PRIVATE
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailSender {

    private const val SMTP_EMAIL = "exceetech@gmail.com"
    private const val SMTP_PASSWORD = "admin    1234" +
            "a"

    fun sendEmail(subject: String, body: String) {

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
                InternetAddress.parse("exceetech@gmail.com")
            )
            setSubject(subject)
            setText(body)
        }

        Transport.send(message)
    }
}