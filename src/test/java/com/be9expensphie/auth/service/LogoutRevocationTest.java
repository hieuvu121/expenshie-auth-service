package com.be9expensphie.auth.service;

import com.be9expensphie.auth.repository.UserRepository;
import com.be9expensphie.auth.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the logout side of JWT revocation.
 *
 * The channel name is duplicated in api-gateway's JwtRevocationListener, which
 * lives in a separate repository -- nothing at build time ties the two
 * together. This test is what stands between a rename here and gateways that
 * silently stop evicting, so the literal is spelled out rather than imported.
 *
 * RedisTemplate is subclassed rather than mocked: it is a concrete class, and
 * RedisConnection's interface hierarchy is too large for the inline mock maker
 * to instrument on the JDK 25 these tests run under. Recording the call by hand
 * needs no bytecode generation and asserts the same thing.
 */
@ExtendWith(MockitoExtension.class)
class LogoutRevocationTest {

    private static final String CHANNEL = "jwt-revoked";
    private static final String SECRET = "test-secret-long-enough-for-hmac-sha256-signing-keys";

    @Mock private UserRepository userRepository;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private HttpServletRequest request;

    private RecordingRedisTemplate redisTemplate;
    private UserService userService;
    private JwtUtil jwtUtil;

    /** Records what logout publishes, and can be told to fail the publish. */
    private static class RecordingRedisTemplate extends RedisTemplate<String, String> {
        private final ValueOperations<String, String> valueOperations;
        final List<String> published = new ArrayList<>();
        boolean publishFails;

        RecordingRedisTemplate(ValueOperations<String, String> valueOperations) {
            this.valueOperations = valueOperations;
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return valueOperations;
        }

        @Override
        public Long convertAndSend(String channel, Object message) {
            if (publishFails) {
                throw new RuntimeException("redis down");
            }
            published.add(channel + "|" + message);
            return 1L;
        }
    }

    @BeforeEach
    void setUp() {
        redisTemplate = new RecordingRedisTemplate(valueOperations);

        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);

        userService = new UserService(userRepository, new BCryptPasswordEncoder(),
                jwtUtil, null, null, redisTemplate);
    }

    private String loggedInToken() {
        String token = jwtUtil.generateToken("user@example.com", 7L);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        return token;
    }

    @Test
    void logoutBlacklistsTheTokenAndAnnouncesItOnTheRevocationChannel() {
        String token = loggedInToken();

        userService.logOut(request);

        verify(valueOperations).set(eq("blacklist:" + token), eq("true"),
                anyLong(), eq(TimeUnit.MILLISECONDS));
        assertThat(redisTemplate.published).containsExactly(CHANNEL + "|" + token);
    }

    @Test
    void aMissingBearerHeaderIsRejectedWithoutTouchingRedis() {
        when(request.getHeader("Authorization")).thenReturn(null);

        assertThatThrownBy(() -> userService.logOut(request))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(valueOperations);
        assertThat(redisTemplate.published).isEmpty();
    }

    /*
     * A publish failure must not fail the logout. The blacklist key is already
     * written, so the token is revoked either way; the gateways just fall back
     * to expiring their cached answer by TTL, which is what they did before
     * pub/sub existed.
     */
    @Test
    void aFailedPublishStillLeavesTheTokenBlacklisted() {
        String token = loggedInToken();
        redisTemplate.publishFails = true;

        userService.logOut(request);

        verify(valueOperations).set(eq("blacklist:" + token), eq("true"),
                anyLong(), eq(TimeUnit.MILLISECONDS));
    }
}
