package com.be9expensphie.auth.service;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.be9expensphie.auth.producer.EmailProducer;
import com.be9expensphie.auth.producer.UserEventProducer;
import com.be9expensphie.common.event.UserEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.be9expensphie.auth.dto.AuthDTO;
import com.be9expensphie.auth.dto.UserDTO;
import com.be9expensphie.auth.entity.UserEntity;
import com.be9expensphie.auth.repository.UserRepository;
import com.be9expensphie.auth.util.JwtUtil;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final EmailProducer emailProducer;
    private final UserEventProducer userEventProducer;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.base-url}")
    private String baseUrl;

    public UserDTO registerUser(UserDTO userDTO) {
        UserEntity newUser = toEntity(userDTO);
        newUser.setActivationToken(UUID.randomUUID().toString());
        newUser = userRepository.save(newUser);

        String activationLink = baseUrl + "/app/v1/activate?token=" + newUser.getActivationToken();
        String subject = "Activate your Expensphie account";
        String body = "Click on the following link to activate your account: " + activationLink;
        try {
            emailProducer.sendEmailEvent(newUser.getEmail(), subject, body, "ACTIVATION");
        } catch (Exception e) {
            System.err.println("Failed to publish activation email event: " + e.getMessage());
        }

        try {
            UserEvent userEvent = UserEvent.builder()
                    .userId(newUser.getId())
                    .email(newUser.getEmail())
                    .fullName(newUser.getFullName())
                    .eventType("USER_REGISTERED")
                    .build();
            userEventProducer.publishUserEvent(userEvent);
        } catch (Exception e) {
            System.err.println("Failed to publish user event: " + e.getMessage());
        }

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

    public boolean isAccountActive(String email) {
        return userRepository.findByEmail(email)
                .map(UserEntity::getIsActive)
                .orElse(false);
    }

    public UserDTO getPublicUser(String email) {
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Account not found with email: " + email));
        return toDTO(user);
    }

    public Map<String, Object> authenticateAndGenerateToken(AuthDTO authDTO) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(authDTO.getEmail(), authDTO.getPassword()));
            UserEntity user = userRepository.findByEmail(authDTO.getEmail())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));
            String token = jwtUtil.generateToken(authDTO.getEmail(), user.getId());
            return Map.of(
                    "token", token,
                    "user", getPublicUser(authDTO.getEmail()));
        } catch (Exception e) {
            throw new RuntimeException("Invalid email or password");
        }
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
        }
    }
}
