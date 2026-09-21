package com.pavan.furniture_ecom.service.impl;

import com.pavan.furniture_ecom.model.User;
import com.pavan.furniture_ecom.repository.UserRepository;
import com.pavan.furniture_ecom.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public User resolveUser(Jwt jwt) {
        String keycloakId = jwt.getSubject();

        return userRepository.findByKeycloakId(keycloakId)
                .map(existing -> syncProfile(existing, jwt))
                .orElseGet(() -> createFromToken(jwt));
    }

    private User createFromToken(Jwt jwt) {
        // Need to add role check

        User user = User.builder()
                .keycloakId(jwt.getSubject())
                .email(jwt.getClaimAsString("email"))
                .firstName(jwt.getClaimAsString("given_name"))
                .lastName(jwt.getClaimAsString("family_name"))
                .active(false)
                .build();
        try{
            return userRepository.save(user);
        }catch (DataIntegrityViolationException ex){
            return userRepository.findByKeycloakId(jwt.getSubject()).orElseThrow();
        }
    }

    private User syncProfile(User user, Jwt jwt) {
        String email = jwt.getClaimAsString("email");

        if(email != null && !email.equals(user.getEmail())) {
            user.setEmail(email);
        }
        return user;
    }
}
