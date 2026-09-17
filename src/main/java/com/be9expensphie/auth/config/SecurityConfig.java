package com.be9expensphie.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /*
     * Every request is permitted here because the gateway has already
     * authenticated it: JwtAuthenticationFilter verifies the token and passes
     * the caller down as X-User-Id. A service reached directly, bypassing the
     * gateway, is therefore unauthenticated by design -- which is why only the
     * gateway's port is published.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Injected by UserService and ForgotPasswordService.
     *
     * The AuthenticationManager and DaoAuthenticationProvider beans that used
     * to sit alongside this one are gone: UserService verifies the hash against
     * the entity it has already loaded, so nothing asked the provider to
     * resolve an account any more and it only cost a duplicate findByEmail.
     * See UserService.authenticateAndGenerateToken.
     *
     * AppUserDetailsService stays a @Service despite now having no injector.
     * It is the UserDetailsService bean that keeps Spring Boot's
     * UserDetailsServiceAutoConfiguration backed off; without it the service
     * would start generating an in-memory user and logging a random password.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
