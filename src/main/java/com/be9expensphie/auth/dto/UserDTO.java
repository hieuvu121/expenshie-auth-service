package com.be9expensphie.auth.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserDTO {
    private Long id;
    private String fullName;
    private String email;
    private String password;
    private String role;
    private String userImageUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
