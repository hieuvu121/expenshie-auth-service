package com.be9expensphie.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.be9expensphie.auth.entity.UserEntity;

import jakarta.transaction.Transactional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findByActivationToken(String activationToken);

    @Transactional
    @Modifying
    @Query("update UserEntity u set u.password = ?2 where u.email = ?1")
    void updatePassword(String email, String password);
}
