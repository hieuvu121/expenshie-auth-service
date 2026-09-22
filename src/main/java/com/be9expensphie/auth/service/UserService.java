package com.be9expensphie.auth.service;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.be9expensphie.auth.outbox.OutboxWriter;
import com.be9expensphie.common.event.EmailEvent;
import com.be9expensphie.common.event.UserEvent;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.be9expensphie.auth.dto.AuthDTO;
import com.be9expensphie.auth.dto.UserDTO;
import com.be9expensphie.auth.entity.UserEntity;
import com.be9expensphie.auth.exception.AccountNotActiveException;
import com.be9expensphie.auth.repository.UserRepository;
import com.be9expensphie.auth.util.JwtUtil;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final OutboxWriter outbox;
    private final RedisTemplate<String, String> redisTemplate;
    private static final String JWT_REVOKED_CHANNEL="jwt-revoked";

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * Registers a user and records both of its events atomically with the row.
     *
     * Both publishes used to happen after save(), each wrapped in a catch that
     * printed to System.err and carried on. A broker outage therefore produced
     * a user in auth_db that household_db.user_summary never learned about --
     * permanently, since UserEventConsumer handles only USER_REGISTERED and
     * nothing replays. The activation email could vanish the same way, leaving
     * an account nobody could activate.
     *
     * @Transactional is new here. It is what gives the outbox rows the same
     * fate as the user row: all three commit, or none of them do.
     */
    @Transactional
    public UserDTO registerUser(UserDTO userDTO) {
        UserEntity newUser = toEntity(userDTO);
        newUser.setActivationToken(UUID.randomUUID().toString());
        newUser = userRepository.save(newUser);

        String activationLink = baseUrl + "/app/v1/activate?token=" + newUser.getActivationToken();
        outbox.write("email-events", newUser.getEmail(), new EmailEvent(
                newUser.getEmail(),
                "Activate your Expensphie account",
                "Click on the following link to activate your account: " + activationLink,
                "ACTIVATION",
                null));

        outbox.write("user-events", newUser.getEmail(), UserEvent.builder()
                .userId(newUser.getId())
                .email(newUser.getEmail())
                .fullName(newUser.getFullName())
                .eventType("USER_REGISTERED")
                .build());

        return toDTO(newUser);
    }

    public UserEntity toEntity(UserDTO userDTO) {
        return UserEntity.builder()
                .id(userDTO.getId())
                .fullName(userDTO.getFullName())
                .email(userDTO.getEmail())
                .password(passwordEncoder.encode(userDTO.getPassword()))
                .role(userDTO.getRole())
                .userImageUrl(userDTO.getUserImageUrl())
                .createdAt(userDTO.getCreatedAt())
                .updatedAt(userDTO.getUpdatedAt())
                .build();
    }

    public UserDTO toDTO(UserEntity userEntity) {
        return UserDTO.builder()
                .id(userEntity.getId())
                .fullName(userEntity.getFullName())
                .email(userEntity.getEmail())
                .role(userEntity.getRole())
                .userImageUrl(userEntity.getUserImageUrl())
                .createdAt(userEntity.getCreatedAt())
                .updatedAt(userEntity.getUpdatedAt())
                .build();
    }

    public boolean activateUser(String activationToken) {
        return userRepository.findByActivationToken(activationToken)
                .map(user -> {
                    user.setIsActive(true);
                    userRepository.save(user);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Authenticates and issues a JWT, reading the user row exactly once.
     *
     * This deliberately does not go through AuthenticationManager. The
     * DaoAuthenticationProvider behind it resolves the account itself via
     * AppUserDetailsService, which was a second identical findByEmail on top of
     * the three this method and the controller already issued between them --
     * four reads of one row, each in its own transaction. Verifying the hash
     * against the entity already in hand collapses that to one.
     *
     * Nothing is lost by skipping the provider: it contributes the same
     * PasswordEncoder bean used here, and AppUserDetailsService builds its
     * UserDetails without ever setting the disabled/locked/expired flags, so
     * the provider's account-status checks could not fail. Its unknown-user
     * timing mitigation does not apply either -- the activation check below
     * answers before any password work, as it always has.
     *
     * LoginQueryCountTest pins both the single read and the responses.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> authenticateAndGenerateToken(AuthDTO authDTO) {
        UserEntity user = userRepository.findByEmail(authDTO.getEmail()).orElse(null);

        /*
         * An unknown email is answered as "not activated", which is what the
         * previous isAccountActive() check did by mapping an empty Optional to
         * false. Kept as-is: the web and mobile clients branch on that 403.
         */
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            throw new AccountNotActiveException(
                    "Account is not yet active. Please activate the account first");
        }

        if (!passwordEncoder.matches(authDTO.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return Map.of(
                "token", jwtUtil.generateToken(user.getEmail(), user.getId()),
                "user", toDTO(user));
    }

    public void logOut(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Invalid authorization header.");
        }

        String token = authHeader.substring(7);
        Date expire = jwtUtil.extractExpiration(token);
        long ttl = expire.getTime() - System.currentTimeMillis();

        if (ttl > 0) {
            redisTemplate.opsForValue().set("blacklist:" + token, "true", ttl, TimeUnit.MILLISECONDS);

            /*
             * Inside the guard, and after the SET.
             *
             * Inside, because announcing a revocation that was never written
             * would have the gateways drop a cached answer and immediately
             * re-read the same "not blacklisted" from Redis -- churn for
             * nothing. After, because a gateway that evicts and re-reads before
             * the key exists caches the stale answer again and holds it for the
             * full TTL, which is the ordering bug expense-service's membership
             * consumer documents.
             *
             * Best-effort: a gateway that misses this still expires its entry
             * within app.jwt.blacklist-cache-ttl-seconds, exactly as it did
             * before pub/sub existed. A failed publish must not fail the logout.
             */
            try {
                redisTemplate.convertAndSend(JWT_REVOKED_CHANNEL, token);
            } catch (Exception e) {
                log.warn("Could not publish revocation; gateways will expire it by TTL", e);
            }
        }
    }
}
