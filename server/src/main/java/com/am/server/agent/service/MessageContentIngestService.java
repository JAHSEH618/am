package com.am.server.agent.service;

import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.agent.config.CaptureProperties;
import com.am.server.agent.ingest.ContentPartFlattener;
import com.am.server.domain.ai.AiSessionMessageBlob;
import com.am.server.domain.ai.AiSessionMessageBlobLink;
import com.am.server.domain.ai.AiSessionMessageBlobLinkRepository;
import com.am.server.domain.ai.AiSessionMessageBlobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

/**
 * 结构化消息内容与 blob 入库。
 * gz
 */
@Service
@RequiredArgsConstructor
public class MessageContentIngestService {

    private final CaptureProperties captureProperties;
    private final AiSessionMessageBlobRepository blobRepository;
    private final AiSessionMessageBlobLinkRepository linkRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record PreparedMessage(
            String contentPartsJson,
            String contentText,
            String contentKind,
            boolean hasBinary,
            int partsCount,
            List<LinkRef> links
    ) {
    }

    public record LinkRef(int partIndex, long blobId) {
    }

    /**
     * 从上报 DTO 构建可落库 parts；legacy 仅 text 时包装为单 part。
     */
    public PreparedMessage prepare(List<ContentPartDto> parts, String legacyText) throws Exception {
        int maxParts = captureProperties.getMaxPartsPerMessage();
        int maxTextBytes = captureProperties.getMaxTextBytesPerPart();
        int maxBlobBytes = captureProperties.getMaxBlobBytesPerPart();
        int maxBlobs = captureProperties.getMaxBlobsPerMessage();

        List<ContentPartDto> normalized = normalizeIncoming(parts, legacyText, maxParts, maxTextBytes);
        if (normalized.isEmpty()) {
            return new PreparedMessage(null, "", "text_only", false, 0, List.of());
        }
        List<LinkRef> links = new ArrayList<>();
        boolean hasBinary = false;
        int blobCount = 0;
        for (int i = 0; i < normalized.size(); i++) {
            ContentPartDto p = normalized.get(i);
            p.setSortOrder(i);
            if (p.getBlobGzipBase64() != null && !p.getBlobGzipBase64().isBlank()) {
                if (blobCount >= maxBlobs) {
                    p.setTruncated(true);
                    p.setTruncateReason("blob_limit");
                    p.setBlobGzipBase64(null);
                    continue;
                }
                byte[] raw = decodeAndMaybeGunzip(p.getBlobGzipBase64());
                if (raw.length > maxBlobBytes) {
                    p.setTruncated(true);
                    p.setTruncateReason("blob_too_large");
                    p.setBlobGzipBase64(null);
                    continue;
                }
                BlobStored stored = storeBlob(raw, p.getMime(), p.getWidth(), p.getHeight());
                p.setBlobId(stored.blobId());
                p.setBlobSha256(stored.sha256());
                p.setBlobGzipBase64(null);
                links.add(new LinkRef(i, stored.blobId()));
                hasBinary = true;
                blobCount++;
            } else if (p.getBlobId() != null && p.getBlobId() > 0) {
                links.add(new LinkRef(i, p.getBlobId()));
                hasBinary = true;
                blobCount++;
            }
        }
        String json = objectMapper.writeValueAsString(normalized);
        String flat = ContentPartFlattener.flatten(normalized);
        String kind = ContentPartFlattener.resolveContentKind(normalized);
        return new PreparedMessage(json, flat, kind, hasBinary, normalized.size(), links);
    }

    public void saveLinks(long messageId, List<LinkRef> links) {
        if (links == null || links.isEmpty()) {
            return;
        }
        for (LinkRef ref : links) {
            AiSessionMessageBlobLink link = new AiSessionMessageBlobLink();
            link.setMessageId(messageId);
            link.setPartIndex(ref.partIndex());
            link.setBlobId(ref.blobId());
            linkRepository.save(link);
        }
    }

    public List<ContentPartDto> parsePartsJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ContentPartDto> normalizeIncoming(List<ContentPartDto> parts, String legacyText,
                                                    int maxParts, int maxTextBytes) {
        if (parts != null && !parts.isEmpty()) {
            List<ContentPartDto> copy = new ArrayList<>(parts);
            if (copy.size() > maxParts) {
                copy = new ArrayList<>(copy.subList(0, maxParts));
            }
            for (ContentPartDto p : copy) {
                if (p.getText() != null && !p.getText().isEmpty()) {
                    p.setText(truncateText(p.getText(), maxTextBytes));
                }
            }
            return copy;
        }
        if (legacyText != null && !legacyText.isBlank()) {
            ContentPartDto p = new ContentPartDto();
            p.setType("text");
            p.setText(truncateText(legacyText, maxTextBytes));
            p.setSortOrder(0);
            return List.of(p);
        }
        return List.of();
    }

    private static String truncateText(String s, int maxBytes) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) {
            return s;
        }
        int end = maxBytes;
        while (end > 0 && (b[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(b, 0, end, StandardCharsets.UTF_8) + "\n…[truncated]";
    }

    private byte[] decodeAndMaybeGunzip(String b64) throws Exception {
        byte[] data = Base64.getDecoder().decode(b64.trim());
        if (data.length >= 2 && data[0] == (byte) 0x1f && data[1] == (byte) 0x8b) {
            return gunzip(data);
        }
        return data;
    }

    private static byte[] gunzip(byte[] gz) throws Exception {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private record BlobStored(long blobId, String sha256) {
    }

    private BlobStored storeBlob(byte[] raw, String mime, Integer width, Integer height) throws Exception {
        String sha = sha256Hex(raw);
        Optional<AiSessionMessageBlob> existing = blobRepository.findByContentSha256(sha);
        if (existing.isPresent()) {
            return new BlobStored(existing.get().getId(), sha);
        }
        byte[] gz = gzip(raw);
        AiSessionMessageBlob blob = new AiSessionMessageBlob();
        blob.setContentSha256(sha);
        blob.setMimeType(mime != null && !mime.isBlank() ? mime : guessMime(raw));
        blob.setByteSize(raw.length);
        blob.setGzipBlob(gz);
        blob.setWidth(width);
        blob.setHeight(height);
        blob = blobRepository.save(blob);
        return new BlobStored(blob.getId(), sha);
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 4 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        return bos.toByteArray();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    private static String guessMime(byte[] raw) {
        if (raw.length >= 8 && raw[0] == (byte) 0x89 && raw[1] == 0x50) {
            return "image/png";
        }
        if (raw.length >= 3 && raw[0] == (byte) 0xFF && raw[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }
}
