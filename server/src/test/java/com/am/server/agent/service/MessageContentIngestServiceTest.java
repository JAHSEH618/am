package com.am.server.agent.service;

import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.agent.config.CaptureProperties;
import com.am.server.domain.ai.AiSessionMessageBlobRepository;
import com.am.server.domain.ai.AiSessionMessageBlobLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class MessageContentIngestServiceTest {

    @Mock
    private AiSessionMessageBlobRepository blobRepository;
    @Mock
    private AiSessionMessageBlobLinkRepository linkRepository;

    private MessageContentIngestService service;

    @BeforeEach
    void setUp() {
        CaptureProperties props = new CaptureProperties();
        props.setMaxTextBytesPerPart(1024);
        props.setMaxBlobBytesPerPart(4096);
        props.setMaxBlobsPerMessage(2);
        props.setMaxPartsPerMessage(4);
        service = new MessageContentIngestService(props, blobRepository, linkRepository);
    }

    @Test
    void prepare_truncates_text_part() throws Exception {
        String longText = "a".repeat(2000);
        MessageContentIngestService.PreparedMessage prepared = service.prepare(
                List.of(part("text", longText)), null);
        assertTrue(prepared.contentText().contains("[truncated]") || prepared.contentText().contains("…"));
    }

    @Test
    void prepare_rejects_oversized_blob() throws Exception {
        byte[] big = new byte[5000];
        ContentPartDto p = new ContentPartDto();
        p.setType("image");
        p.setBlobGzipBase64(gzipB64(big));
        MessageContentIngestService.PreparedMessage prepared = service.prepare(List.of(p), null);
        assertEquals(1, prepared.partsCount());
        assertTrue(prepared.contentPartsJson().contains("blob_too_large"));
    }

    private static ContentPartDto part(String type, String text) {
        ContentPartDto p = new ContentPartDto();
        p.setType(type);
        p.setText(text);
        return p;
    }

    private static String gzipB64(byte[] raw) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }
}
