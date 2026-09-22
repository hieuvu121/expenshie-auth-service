package com.be9expensphie.auth.service;

import com.be9expensphie.auth.dto.UserDTO;
import com.be9expensphie.auth.entity.UserEntity;
import com.be9expensphie.auth.outbox.OutboxWriter;
import com.be9expensphie.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration must record both of its events rather than firing them at Kafka.
 *
 * The old code published after save() inside a try/catch that only printed to
 * System.err, so a broker outage left a user in auth_db that
 * household_db.user_summary never learned about -- and UserEventConsumer
 * handles only USER_REGISTERED, so there was no second chance. OutboxWriterTest
 * covers the transactional half; this covers what registerUser hands it.
 *
 * OutboxWriter is subclassed rather than mocked: it is a concrete class, and
 * the inline mock maker cannot instrument those on JDK 25.
 */
@ExtendWith(MockitoExtension.class)
class RegisterOutboxTest {

    @Mock private UserRepository userRepository;

    private RecordingOutbox outbox;
    private UserService userService;

    private record Written(String topic, String key, Object payload) {}

    private static class RecordingOutbox extends OutboxWriter {
        final List<Written> written = new ArrayList<>();

        RecordingOutbox() {
            super(null, null);
        }

        @Override
        public void write(String topic, String aggregateId, Object event) {
            written.add(new Written(topic, aggregateId, event));
        }
    }

    @BeforeEach
    void setUp() {
        outbox = new RecordingOutbox();
        userService = new UserService(userRepository, new BCryptPasswordEncoder(),
                null, outbox, null);
        ReflectionTestUtils.setField(userService, "baseUrl", "http://localhost:8080");

        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });
    }

    private static UserDTO request() {
        return UserDTO.builder()
                .fullName("Dana")
                .email("dana@example.com")
                .password("correct-horse-battery-staple")
                .role("ROLE_USER")
                .build();
    }

    private UserEntity savedUser() {
        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void registrationRecordsTheUserEventAndTheActivationEmail() {
        userService.registerUser(request());

        assertThat(outbox.written).extracting(Written::topic)
                .containsExactlyInAnyOrder("email-events", "user-events");
        assertThat(outbox.written).extracting(Written::key)
                .containsOnly("dana@example.com");
    }

    /*
     * The activation link embeds the token that was just persisted. Building it
     * from the saved entity is what makes the email and the row agree; the
     * outbox is what makes them agree atomically.
     */
    @Test
    void theActivationEmailCarriesTheTokenThatWasSaved() {
        userService.registerUser(request());

        String token = savedUser().getActivationToken();
        assertThat(token).isNotBlank();

        assertThat(outbox.written)
                .filteredOn(w -> w.topic().equals("email-events"))
                .singleElement()
                .satisfies(w -> assertThat(w.payload().toString()).contains("token=" + token));
    }

    @Test
    void theUserEventCarriesTheIdTheDatabaseAssigned() {
        userService.registerUser(request());

        assertThat(outbox.written)
                .filteredOn(w -> w.topic().equals("user-events"))
                .singleElement()
                .satisfies(w -> assertThat(w.payload().toString())
                        .contains("userId=7", "USER_REGISTERED"));
    }

    @Test
    void thePasswordIsHashedBeforeItIsStored() {
        userService.registerUser(request());

        assertThat(savedUser().getPassword())
                .isNotEqualTo("correct-horse-battery-staple")
                .startsWith("$2");
    }
}
