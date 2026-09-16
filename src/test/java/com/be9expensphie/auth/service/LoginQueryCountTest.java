package com.be9expensphie.auth.service;

import com.be9expensphie.auth.controller.UserController;
import com.be9expensphie.auth.dto.AuthDTO;
import com.be9expensphie.auth.entity.UserEntity;
import com.be9expensphie.auth.repository.UserRepository;
import com.be9expensphie.auth.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the cost and the contract of POST /auth/login.
 *
 * Wires the real UserController, UserService, JwtUtil and
 * BCryptPasswordEncoder, mocking only UserRepository. That is what makes the
 * query count meaningful: every read the login path issues lands on the same
 * mock, so verify() sees all of them.
 *
 * The Kafka producers and RedisTemplate are passed as null on purpose. Login
 * must not touch them, so a null is a sharper assertion than a mock would be:
 * if the path ever grows a publish, this test fails with an NPE rather than
 * silently recording it. They are also concrete classes, which the inline mock
 * maker cannot instrument on the JDK 25 these tests run under.
 */
@ExtendWith(MockitoExtension.class)
class LoginQueryCountTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String SECRET = "test-secret-long-enough-for-hmac-sha256-signing-keys";

    @Mock private UserRepository userRepository;

    private UserController controller;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();

        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);

        UserService userService = new UserService(
                userRepository, passwordEncoder, jwtUtil, null, null, null);
        ReflectionTestUtils.setField(userService, "baseUrl", "http://localhost:8080");

        controller = new UserController(userService);
    }

    private UserEntity user(Boolean isActive) {
        return UserEntity.builder()
                .id(7L)
                .fullName("Test User")
                .email(EMAIL)
                .password(passwordEncoder.encode(PASSWORD))
                .role("ROLE_USER")
                .isActive(isActive)
                .build();
    }

    private ResponseEntity<Map<String, Object>> login(String password) {
        return controller.login(AuthDTO.builder().email(EMAIL).password(password).build());
    }

    @Test
    void successfulLoginReadsTheUserRowExactlyOnce() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        ResponseEntity<Map<String, Object>> response = login(PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(userRepository, times(1)).findByEmail(EMAIL);
    }

    @Test
    void successfulLoginReturnsTokenAndPublicUser() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        ResponseEntity<Map<String, Object>> response = login(PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("token", "user");
        assertThat(response.getBody().get("token")).asString().isNotEmpty();
        assertThat(response.getBody().get("user")).hasFieldOrPropertyWithValue("email", EMAIL);
    }

    @Test
    void wrongPasswordIsRejectedWithoutSayingWhichFieldWasWrong() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        ResponseEntity<Map<String, Object>> response = login("wrong-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Invalid email or password");
    }

    @Test
    void inactiveAccountIsRejectedBeforeAnyTokenIsIssued() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(null)));

        ResponseEntity<Map<String, Object>> response = login(PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry(
                "message", "Account is not yet active. Please activate the account first");
    }

    /*
     * Documents existing behaviour rather than endorsing it: an unknown email
     * is answered with the same 403 as a known-but-inactive one, which tells a
     * caller nothing but also distinguishes neither. Changing it is a contract
     * change for the web and mobile clients, so it is out of scope here — this
     * test exists so the refactor cannot alter it by accident.
     */
    @Test
    void unknownEmailIsAnsweredAsInactiveAccount() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = login(PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(userRepository, times(1)).findByEmail(EMAIL);
    }
}
