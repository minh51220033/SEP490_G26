package com.cvdcms.service;

public interface EmailService {
    void sendSimpleMail(String to, String subject, String text);
    void sendEmail(String email, String message);
    String generateRandom();
}