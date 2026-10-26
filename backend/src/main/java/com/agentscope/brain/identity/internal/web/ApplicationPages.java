package com.agentscope.brain.identity.internal.web;

import com.agentscope.brain.identity.internal.readiness.StartupReadiness;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class ApplicationPages {
    private final StartupReadiness readiness;
    public ApplicationPages(StartupReadiness readiness) { this.readiness = readiness; }
    @GetMapping({"/", "/login", "/password/initial", "/workbench"})
    public String application() { return "forward:/index.html"; }
    @GetMapping("/readyz") @ResponseBody
    public ResponseEntity<?> ready() { return ResponseEntity.status(readiness.ready() ? 200 : 503).body(java.util.Map.of("ready", readiness.ready())); }
}
