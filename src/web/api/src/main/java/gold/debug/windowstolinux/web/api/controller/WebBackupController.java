package gold.debug.windowstolinux.web.api.controller;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/backups")
public final class WebBackupController {
    private final WebApplicationService service;
    private final WebRequestContext context;
    public WebBackupController(WebApplicationService service, WebRequestContext context) { this.service = service; this.context = context; }

    @GetMapping public JsonNode list() throws Exception { return service.listBackups(context); }
    @PutMapping(value = "/upload", consumes = "application/octet-stream") @ResponseStatus(HttpStatus.CREATED)
    public JsonNode upload(@RequestParam String name, jakarta.servlet.http.HttpServletRequest request) throws Exception {
        return service.uploadBackup(context, name, request.getInputStream());
    }
    @GetMapping(value = "/{id}/download", produces = "application/zip")
    public void download(@PathVariable String id, jakarta.servlet.http.HttpServletResponse response) throws Exception {
        var file = service.downloadBackup(context, id);
        response.setContentType("application/zip"); response.setContentLengthLong(java.nio.file.Files.size(file));
        response.setHeader("Content-Disposition", "attachment; filename=\"backup.zip\"");
        try (var input = java.nio.file.Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            input.transferTo(response.getOutputStream());
        }
    }
}
