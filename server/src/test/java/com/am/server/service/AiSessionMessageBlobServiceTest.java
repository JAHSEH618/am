package com.am.server.service;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageBlob;
import com.am.server.domain.ai.AiSessionMessageBlobLinkRepository;
import com.am.server.domain.ai.AiSessionMessageBlobRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSessionMessageBlobServiceTest {

    private final AiSessionMessageRepository messages = mock(AiSessionMessageRepository.class);
    private final AiSessionMessageBlobRepository blobs = mock(AiSessionMessageBlobRepository.class);
    private final AiSessionMessageBlobLinkRepository links = mock(AiSessionMessageBlobLinkRepository.class);
    private final AiSessionMessageBlobService service = new AiSessionMessageBlobService(messages, blobs, links);

    private void linked() {
        AiSessionMessage m = new AiSessionMessage();
        m.setId(5L);
        m.setAiSessionId(1L);
        when(messages.findById(5L)).thenReturn(Optional.of(m));
        when(links.existsByMessageIdAndBlobId(5L, 9L)).thenReturn(true);
    }

    @Test
    void blobClearedByRetentionIsReportedAsNotFoundNotAsAServerError() {
        linked();
        AiSessionMessageBlob expired = new AiSessionMessageBlob();
        expired.setId(9L);
        expired.setGzipBlob(new byte[0]);
        when(blobs.findById(9L)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.load(1L, 5L, 9L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void intactBlobStillDecompresses() throws Exception {
        linked();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write("png-bytes".getBytes());
        }
        AiSessionMessageBlob ok = new AiSessionMessageBlob();
        ok.setId(9L);
        ok.setMimeType("image/png");
        ok.setGzipBlob(bos.toByteArray());
        when(blobs.findById(9L)).thenReturn(Optional.of(ok));

        AiSessionMessageBlobService.BlobPayload payload = service.load(1L, 5L, 9L);

        assertThat(new String(payload.bytes())).isEqualTo("png-bytes");
        assertThat(payload.mimeType()).isEqualTo("image/png");
    }
}
