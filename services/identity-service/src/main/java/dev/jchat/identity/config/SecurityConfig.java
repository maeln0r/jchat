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

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
            Set<GrantedAuthority> auths = new HashSet<>();

            Map<String, Object> realm = jwt.getClaim("realm_access");
            if (realm != null && realm.get("roles") instanceof Collection<?> roles) {
                roles.forEach(r -> auths.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }

            Map<String, Object> ra = jwt.getClaim("resource_access");
            if (ra != null) {
                Object clientBlock = ra.get("identity-service");
                if (clientBlock instanceof Map<?, ?> cb) {
                    Object cr = cb.get("roles");
                    if (cr instanceof Collection<?> roles) {
                        roles.forEach(r -> auths.add(new SimpleGrantedAuthority("SCOPE_" + r)));
                    }
                }
            }

            JwtGrantedAuthoritiesConverter def = new JwtGrantedAuthoritiesConverter();
            auths.addAll(def.convert(jwt));

            return auths;
        });


        http.oauth2ResourceServer(oauth -> oauth.jwt(j -> j.jwtAuthenticationConverter(conv)));
        return http.build();
    }
}
