package com.pavan.furniture_ecom.keycloak;

import com.pavan.furniture_ecom.config.KeycloakProperties;
import com.pavan.furniture_ecom.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class KeycloakAdminClient {
    private final RestClient restClient;
    private final KeycloakProperties keycloakProperties;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public String createUser(String email, String firstName, String lastName, String password){
        Map<String, Object> payload = Map.of(
                "username", email,
                "email", email,
                "firstName", firstName,
                "lastName", lastName,
                "enabled", true,
                "emailVerified", false,
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", password,
                        "temporary", false
                ))
        );

        ResponseEntity<Void> response = restClient.post()
                .uri(keycloakProperties.adminUsersUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .onStatus(status -> status.value() == 409,
                        (req, res) -> {
                    throw new AppException("Email already registered", HttpStatus.CONFLICT,
                            "USER_ALREADY_EXISTS");
                })
                .toBodilessEntity();

        URI location = response.getHeaders().getLocation();

        if(location == null) {
            throw new AppException("Keycloak did not return a user id",
                    HttpStatus.INTERNAL_SERVER_ERROR, "KEYCLOAK_ERROR");
        }

        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // compensating action when the local insert fails after the keycloak user was created
    public void deleteUser(String keycloakId) {
        restClient.delete()
                .uri(keycloakProperties.adminUsersUri() + "/" + keycloakId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .retrieve()
                .toBodilessEntity();
    }

    private String adminToken() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId("keycloak-admin")
                .principal("furniture-ecom-backend")
                .build();

        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(request);
        if(authorizedClient == null) {
            throw new AppException("Could not obtain Keycloak admin token",
                    HttpStatus.SERVICE_UNAVAILABLE, "KEYCLOAK_UNAVAILABLE");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
