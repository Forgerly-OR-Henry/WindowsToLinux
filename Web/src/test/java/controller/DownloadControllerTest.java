package controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class DownloadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void testDownloadExe() throws Exception {
        mockMvc.perform(get("/api/download/exe"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"dlprojx-v2.4.0.exe\""));
    }

    @Test
    public void testDownloadJar() throws Exception {
        mockMvc.perform(get("/api/download/jar"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"dlprojx-v2.4.0.jar\""));
    }

    @Test
    public void testDownloadInvalid() throws Exception {
        mockMvc.perform(get("/api/download/txt"))
                .andExpect(status().isBadRequest());
    }
}
