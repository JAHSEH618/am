package com.am.server.service;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageBlob;
import com.am.server.domain.ai.AiSessionMessageBlobLinkRepository;
import com.am.server.domain.ai.AiSessionMessageBlobRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;

/**
 * 会话消息 blob 读取（详情页图片等）。
 * gz
 */
@Service
@RequiredArgsConstructor
public class AiSessionMessageBlobService {

    private final AiSessionMessageRepository messageRepository;
    private final AiSessionMessageBlobRepository blobRepository;
    private final AiSessionMessageBlobLinkRepository linkRepository;

    public record BlobPayload(byte[] bytes, String mimeType) {
    }

    public BlobPayload load(long sessionId, long messageId, long blobId) {
        AiSessionMessage msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                        "ai_session_message not found: " + messageId));
        if (!sessionIdEquals(msg.getAiSessionId(), sessionId)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "ai_session_message not found: " + messageId);
        }
        if (!linkRepository.existsByMessageIdAndBlobId(messageId, blobId)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "blob not linked to message: " + blobId);
        }
        AiSessionMessageBlob blob = blobRepository.findById(blobId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                        "ai_session_message_blob not found: " + blobId));
        try {
            byte[] raw = gunzip(blob.getGzipBlob());
            return new BlobPayload(raw, blob.getMimeType());
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "blob decompress failed: " + blobId);
        }
    }

    private static boolean sessionIdEquals(Long actual, long expected) {
        return actual != null && actual == expected;
    }

    private static byte[] gunzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
