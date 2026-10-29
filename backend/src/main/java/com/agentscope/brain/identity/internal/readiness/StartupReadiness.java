package com.agentscope.brain.identity.internal.readiness;

import com.agentscope.brain.identity.internal.application.AuthenticationService;
import com.agentscope.brain.identity.internal.persistence.IdentityRepository;
import com.agentscope.brain.release.api.ReleaseReadiness;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only, fail-closed checks. No schema creation, account repair or external service probes. */
@Component
public class StartupReadiness implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(StartupReadiness.class);
    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;
    private final ReleaseReadiness release;
    private final ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Value("${brain.copilot.enabled:false}")
    private boolean copilotEnabled;
    @org.springframework.beans.factory.annotation.Value("${brain.copilot.runtime-secret:}")
    private String copilotRuntimeSecret;
    private volatile boolean ready;
    private volatile List<String> gaps = List.of("STARTING");
    public StartupReadiness(JdbcTemplate jdbc, IdentityRepository identities, ReleaseReadiness release, ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.identities = identities; this.release = release; this.events = events;
    }
    public boolean ready() { return ready; }
    public List<String> gaps() { return gaps; }

    // Boot emits ACCEPTING_TRAFFIC after ApplicationReadyEvent listeners return. Preserve refusal
    // after ApplicationAvailabilityBean has observed that later event as well.
    @EventListener @Order(Ordered.LOWEST_PRECEDENCE)
    public void preserveRefusal(AvailabilityChangeEvent<?> event) {
        if (!ready && event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
            events.publishEvent(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        }
    }

    @Override public void onApplicationEvent(ApplicationReadyEvent event) {
        var missing = new ArrayList<String>();
        try {
            checkStructures(missing);
            if (missing.isEmpty()) {
                var ids = jdbc.queryForList("SELECT CAST(id AS CHAR) FROM platform_user", String.class);
                if (ids.size() != 1 || jdbc.queryForObject("SELECT COUNT(*) FROM fixed_admin_assignment", Long.class) != 0) {
                    missing.add("PRESET_SINGLE_ORDINARY_USER");
                } else {
                    var users = identities.byId(ids.get(0));
                    if (users.size() != 1) { missing.add("PRESET_IDENTITY_REFERENCES"); }
                    else { AuthenticationService.requireComplete(users.get(0)); }
                    release.verify(ids.get(0));
                }
            }
        } catch (com.agentscope.brain.identity.internal.domain.AuthFailure failure) {
            missing.add(failure.getMessage());
        } catch (ReleaseReadiness.InvalidRelease failure) {
            missing.add(failure.getMessage());
        } catch (Exception failure) {
            // Log only the exception class, never driver messages containing connections or SQL parameters.
            missing.add("STARTUP_INPUT_UNAVAILABLE:" + failure.getClass().getSimpleName());
        }
        gaps = List.copyOf(missing);
        ready = gaps.isEmpty();
        LOG.info("Authentication readiness: {} {}", ready ? "READY" : "REFUSED", gaps);
        AvailabilityChangeEvent.publish(event.getApplicationContext(), ready ? ReadinessState.ACCEPTING_TRAFFIC : ReadinessState.REFUSING_TRAFFIC);
    }

    private void checkStructures(List<String> missing) throws Exception {
        if (copilotEnabled) {
            if (copilotRuntimeSecret == null || copilotRuntimeSecret.length() < 32) missing.add("COPILOT_RUNTIME_SECRET_MISSING");
            Long copilotTables = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                    AND LOWER(table_name) IN ('copilot_thread','copilot_thread_message','copilot_thread_run')
                    """, Long.class);
            if (copilotTables == null || copilotTables != 3) missing.add("COPILOT_STRUCTURE_MISSING");
        }
        // #34's exact login baseline and the exact 01..10 workbench baseline are distinct
        // supported revisions. A partially installed history revision must fail closed.
        boolean historyRevision = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                AND LOWER(table_name) IN ('session_projection_event','projection_content','projection_content_version')
                """, Long.class) > 0;
        var resource = new ClassPathResource(historyRevision
                ? (copilotEnabled ? "readiness/structure-workbench-v1.tsv" : "readiness/structure-workbench-v0.tsv")
                : "readiness/structure-v1.tsv");
        checkResource(resource, missing);
        if (copilotEnabled && !historyRevision)
            checkResource(new ClassPathResource("readiness/structure-copilot-v1.tsv"), missing);
    }

    private void checkResource(ClassPathResource resource, List<String> missing) throws Exception {
        try (var stream = resource.getInputStream()) {
            for (var line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) { continue; }
                var parts = line.split("\\t", 3);
                var actual = jdbc.queryForList(parts[1], String.class, parts[0]);
                var expected = parts[2].isEmpty() ? List.<String>of() : List.of(parts[2].split("~", -1));
                if (!actual.equals(expected) && !missing.contains("STRUCTURE_MISMATCH:" + parts[0])) {
                    missing.add("STRUCTURE_MISMATCH:" + parts[0]);
                }
            }
        }
    }
}
