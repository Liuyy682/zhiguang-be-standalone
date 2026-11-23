package com.tongji.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/agent", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentController {

    private final AgentLoop agentLoop;

    public AgentController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AgentLoop.Result chat(@Valid @RequestBody ChatRequest request) {
        return agentLoop.run(request.message());
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {
    }
}
