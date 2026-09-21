package com.pavan.furniture_ecom.keycloak;

import com.pavan.furniture_ecom.config.KeycloakProperties;
import com.pavan.furniture_ecom.dto.auth.TokenResponse;
import com.pavan.furniture_ecom.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class KeycloakAuthClient {

    private final RestClient restClient;
    private final KeycloakProperties keycloakProperties;

    public TokenResponse passwordGrant(String username, String password){
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", keycloakProperties.getClientId());
        form.add("client_secret", keycloakProperties.getClientSecret());
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid profile email");

        return postForToken(form, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    public TokenResponse refresh(String refreshToken){
        MultiValueMap<String, String > form =  new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", keycloakProperties.getClientId());
        form.add("client_secret", keycloakProperties.getClientSecret());
        form.add("refresh_token", refreshToken);
        form.add("scope", "openid profile email");
        return postForToken(form, "INVALID_CREDENTIALS", "Refresh token is invalid or expired");
    }

    public void logout(String refreshToken){
        MultiValueMap<String, String > form =  new LinkedMultiValueMap<>();
        form.add("client_id", keycloakProperties.getClientId());
        form.add("client_secret", keycloakProperties.getClientSecret());
        form.add("refresh_token", refreshToken);

        restClient.post()
                .uri(keycloakProperties.logoutUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }

    private TokenResponse postForToken(MultiValueMap<String, String> form, String errorCode, String message) {
        return restClient.post()
                .uri(keycloakProperties.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new AppException(message, HttpStatus.UNAUTHORIZED, errorCode);
                })
                .body(TokenResponse.class);
    }
}
