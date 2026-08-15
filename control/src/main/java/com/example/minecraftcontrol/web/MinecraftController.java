package com.example.minecraftcontrol.web;

import com.example.minecraftcontrol.service.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MinecraftController {
    private final MinecraftControlService service;
    public MinecraftController(MinecraftControlService service) { this.service = service; }

    @GetMapping("/status") public ServerStatus status() { return service.status(); }
    @GetMapping("/diagnostics") public Diagnostics diagnostics() { return service.diagnostics(); }
    @PostMapping("/start") public ControlResult start() { return service.start("local REST API"); }
    @PostMapping("/stop") public ControlResult stop() { return service.stop("local REST API"); }
    @PostMapping("/restart") public ControlResult restart() { return service.restart("local REST API"); }
}
