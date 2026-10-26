package com.agentscope.brain.identity.internal.security;

import com.agentscope.brain.identity.internal.application.AuthenticationService;
import com.agentscope.brain.identity.internal.domain.AuthFailure;
import com.agentscope.brain.identity.internal.domain.PlatformPrincipal;
import com.agentscope.brain.identity.internal.readiness.StartupReadiness;
import com.agentscope.brain.identity.internal.web.AuthResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
class AuthenticationConfiguration {
    @Bean Clock authenticationClock() { return Clock.systemUTC(); }
    @Bean SecurityContextRepository authenticationContexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean CsrfTokenRepository authenticationCsrf() { return new HttpSessionCsrfTokenRepository(); }
    @Bean UserDetailsService noImplicitAccounts() {
        return name -> { throw new UsernameNotFoundException("No implicit platform accounts"); };
    }

    @Bean
    SecurityFilterChain applicationAccess(HttpSecurity http, AuthenticationService authentication,
            SecurityContextRepository contexts, CsrfTokenRepository csrf) throws Exception {
        http.requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable());
        http.securityContext(c -> c.securityContextRepository(contexts).requireExplicitSave(true));
        http.csrf(c -> c.csrfTokenRepository(csrf));
        http.exceptionHandling(c -> c
                .authenticationEntryPoint((req, res, e) -> AuthResponses.error(res, 401, "AUTHENTICATION_REQUIRED"))
                .accessDeniedHandler((req, res, e) -> AuthResponses.error(res, 403,
                        e instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF_REJECTED" : "FORBIDDEN")));
        http.headers(c -> c.contentSecurityPolicy(policy -> policy.policyDirectives(
                "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'")));
        http.addFilterBefore(new OncePerRequestFilter() {
            private final Set<String> restricted = Set.of("GET /api/me", "GET /api/auth/csrf",
                    "POST /api/auth/password/initial", "POST /api/auth/logout");
            @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                if (!req.getServletPath().startsWith("/api/") || req.getServletPath().equals("/api/auth/login")) {
                    chain.doFilter(req, res); return;
                }
                try {
                    var context = SecurityContextHolder.getContext().getAuthentication();
                    if (context != null && context.getPrincipal() instanceof PlatformPrincipal principal) {
                        var user = authentication.current(principal);
                        if (user.changeRequired() && !restricted.contains(req.getMethod() + " " + req.getServletPath())) {
                            AuthResponses.error(res, 403, "PASSWORD_CHANGE_REQUIRED"); return;
                        }
                    }
                    chain.doFilter(req, res);
                } catch (AuthFailure failure) {
                    if (failure.status() == 401) {
                        SecurityContextHolder.clearContext();
                        if (req.getSession(false) != null) { req.getSession(false).invalidate(); }
                    }
                    AuthResponses.error(res, failure.status(), failure.getMessage());
                }
            }
        }, AuthorizationFilter.class);
        http.authorizeHttpRequests(c -> c
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/auth/csrf", "/readyz", "/", "/index.html", "/login", "/password/initial", "/workbench", "/assets/**", "/favicon.ico").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/me", "/api/auth/logout", "/api/auth/password/initial").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }

    /** Wrap SessionRepositoryFilter too: database failure while reading/saving sessions must never become a login success. */
    @Bean FilterRegistrationBean<OncePerRequestFilter> authInfrastructureBoundary(StartupReadiness readiness) {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                if (req.getRequestURI().startsWith("/api/")) {
                    res.setHeader("Cache-Control", "no-store");
                    res.setHeader("Pragma", "no-cache");
                    if (!readiness.ready()) { AuthResponses.error(res, 503, "APPLICATION_NOT_READY"); return; }
                }
                try { chain.doFilter(req, res); }
                catch (DataAccessException failure) {
                    SecurityContextHolder.clearContext();
                    if (res.isCommitted()) { throw failure; }
                    res.resetBuffer();
                    AuthResponses.error(res, 503, "AUTH_DATA_UNAVAILABLE");
                }
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        return registration;
    }
}
