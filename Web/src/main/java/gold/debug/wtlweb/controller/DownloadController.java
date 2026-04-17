package gold.debug.wtlweb.controller;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/download")
public class DownloadController {

    private static final List<String> ALLOWED_FORMATS = Arrays.asList("exe", "msi", "zip", "jar");
    private final Path fileStoragePath = Paths.get("src/main/resources/files");

    @GetMapping("/{format}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String format) throws MalformedURLException {
        if (!ALLOWED_FORMATS.contains(format.toLowerCase())) {
            return ResponseEntity.badRequest().build();
        }

        String filename = "dlprojx-v2.4.0." + format;
        Path filePath = fileStoragePath.resolve(filename).normalize();
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}