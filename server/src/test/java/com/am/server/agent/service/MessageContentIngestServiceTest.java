package com.am.server.agent.service;

import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.agent.config.CaptureProperties;
import com.am.server.domain.ai.AiSessionMessageBlob;
import com.am.server.domain.ai.AiSessionMessageBlobRepository;
import com.am.server.domain.ai.AiSessionMessageBlobLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void storeBlob_refillsABlobWhoseBytesWereClearedByRetention_insteadOfLinkingToAnEmptyOne() throws Exception {
        // 保留期清理只清 gzip_blob 字节、保留行（sha 唯一键）；同一份内容再次上报时必须把字节补回来
        AiSessionMessageBlob expired = new AiSessionMessageBlob();
        expired.setId(77L);
        expired.setGzipBlob(new byte[0]);
        when(blobRepository.findByContentSha256(any())).thenReturn(Optional.of(expired));

        MessageContentIngestService.PreparedMessage prepared =
                service.prepare(List.of(imagePart(gzipB64("same-image-bytes".getBytes()))), null);

        assertEquals(77L, prepared.links().get(0).blobId());
        assertTrue(expired.getGzipBlob().length > 0, "字节已补回");
        verify(blobRepository).save(expired);
    }

    @Test
    void storeBlob_reusesAnIntactBlobWithoutWritingAnything() throws Exception {
        AiSessionMessageBlob intact = new AiSessionMessageBlob();
        intact.setId(78L);
        intact.setGzipBlob(new byte[]{1, 2, 3});
        when(blobRepository.findByContentSha256(any())).thenReturn(Optional.of(intact));

        MessageContentIngestService.PreparedMessage prepared =
                service.prepare(List.of(imagePart(gzipB64("same-image-bytes".getBytes()))), null);

        assertEquals(78L, prepared.links().get(0).blobId());
        assertEquals(3, intact.getGzipBlob().length);
        verify(blobRepository, never()).save(any());
    }

    private static ContentPartDto imagePart(String gzipBase64) {
        ContentPartDto p = new ContentPartDto();
        p.setType("image");
        p.setMime("image/png");
        p.setBlobGzipBase64(gzipBase64);
        return p;
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
