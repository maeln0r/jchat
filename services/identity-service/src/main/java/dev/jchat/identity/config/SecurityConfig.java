package dev.jchat.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.*;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        http.authorizeHttpRequests(reg -> reg
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated());

        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> auths = new ArrayList<>();
            // realm roles
            var realm = (Map<String, Object>) jwt.getClaim("realm_access");
            if (realm != null && realm.get("roles") instanceof Collection<?> roles) {
                roles.forEach(r -> auths.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            // client roles (example: app-api)
            var ra = (Map<String, Object>) jwt.getClaim("resource_access");
            if (ra != null && ra.get("identity-service") instanceof Map<?, ?> client) {
                var cr = (Collection<?>) ((Map<?, ?>) client).get("roles");
                if (cr != null) cr.forEach(r -> auths.add(new SimpleGrantedAuthority("SCOPE_" + r)));
            }
            // plus default scope converter
            JwtGrantedAuthoritiesConverter def = new JwtGrantedAuthoritiesConverter();
            auths.addAll(def.convert(jwt));
            return auths;
        });

        http.oauth2ResourceServer(oauth -> oauth.jwt(j -> j.jwtAuthenticationConverter(conv)));
        return http.build();
    }
}
