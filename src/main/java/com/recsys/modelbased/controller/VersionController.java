package com.recsys.modelbased.controller;

import com.recsys.modelbased.dto.ModelVersionRequest;
import com.recsys.modelbased.dto.ModelVersionResponse;
import com.recsys.modelbased.service.ModelVersionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/model/versions")
public class VersionController {

    private final ModelVersionService versionService;

    public VersionController(ModelVersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ModelVersionResponse listVersions() {
        return versionService.listVersions();
    }

    @PostMapping(
            value = "/preload",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ModelVersionResponse preload(@Valid @RequestBody ModelVersionRequest request) {
        return versionService.preload(request.variant());
    }

    @PostMapping(
            value = "/activate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ModelVersionResponse activate(@Valid @RequestBody ModelVersionRequest request) {
        return versionService.activate(request.variant());
    }

    @PostMapping(value = "/rollback", produces = MediaType.APPLICATION_JSON_VALUE)
    public ModelVersionResponse rollback() {
        return versionService.rollback();
    }
}
