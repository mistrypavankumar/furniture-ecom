package com.pavan.furniture_ecom.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "keycloak")
@Component
@Data
public class KeycloakProperties {
    private Admin admin = new Admin();
    private String clientId;
    private String clientSecret;

    @Data
    public static class Admin {
        private String baseUrl;
        private String realm;
    }

    public String tokenUrl(){
        return admin.getBaseUrl() + "/realms/" + admin.getRealm() + "/protocol/openid-connect/token";
    }

    public String logoutUri(){
        return admin.getBaseUrl() + "/realms/" +  admin.getRealm() + "/protocol/openid-connect/logout";
    }

    public String adminUsersUri(){
        return admin.getBaseUrl() + "/admin/realms/" + admin.getRealm() + "/users";
    }

}
