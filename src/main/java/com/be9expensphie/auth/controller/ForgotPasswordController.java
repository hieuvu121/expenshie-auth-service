package com.be9expensphie.auth.controller;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.be9expensphie.auth.service.ForgotPasswordService;
import com.be9expensphie.auth.util.ChangePasswordRecord;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/auth/forgot-password")
@RequiredArgsConstructor
public class ForgotPasswordController {
    private final ForgotPasswordService forgotPasswordService;

    @PostMapping("/verify-email/{email}")
    public ResponseEntity<String> verifyEmail(@PathVariable String email) {
        try {
            forgotPasswordService.verifyEmail(email);
            return ResponseEntity.ok("Email sent for verification!");
        } catch (Exception e) {
            throw new RuntimeException("Verification failed");
        }
    }

    @PostMapping("/verify-otp/{otp}/{email}")
    public ResponseEntity<String> verifyOtp(@PathVariable Integer otp, @PathVariable String email) {
        if (forgotPasswordService.verifyOtp(otp, email)) {
            return ResponseEntity.ok("OTP verified");
        }
        return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).body("OTP has expired!");
    }

    @PostMapping("/change-password/{email}")
    public ResponseEntity<String> changePassword(
            @RequestBody ChangePasswordRecord changePasswordRecord,
            @PathVariable String email) {
        if (!Objects.equals(changePasswordRecord.password(), changePasswordRecord.repeatPassword())) {
            return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).body("Please make sure the passwords match.");
        }
        forgotPasswordService.changePassword(email, changePasswordRecord.password());
        return ResponseEntity.ok("Password changed successfully");
    }
}
