// compress.go gzip 压缩辅助。
//
// <p>员工规模上行带宽不容忽视：100 个员工 × 10s 一次心跳 × 平均 10KB body = ≈ 1 MB/s
// 持续传输；JSON 文本 gzip 比通常 5~10x，启用后峰值带宽可下降到 100~200 KB/s 量级。
//
// <p>策略：body 字节数 ≥ {@link CompressMinBytes} 才压（小心跳包压缩节约 1 KB 不值得 CPU）。
// 压缩后 HMAC 直接对压缩字节算，请求带 {@code Content-Encoding: gzip}，server 端拦截器在
// HMAC 通过后解压交给 controller。
//
// gz
package reporter

import (
	"bytes"
	"compress/gzip"
	"io"
)

// CompressMinBytes 触发 gzip 的最小 body 字节数。低于此阈值不压缩，避免 CPU 开销大于收益。
const CompressMinBytes = 1024

// EncodingGzip Content-Encoding 头值，与 server 端 GzipDecodingFilter 期望对齐。
const EncodingGzip = "gzip"

// maybeCompress 视情况对 body 做 gzip 压缩。
//
// 返回值：
//   - out      可能压缩后的 body 字节
//   - encoding 当 out 是 gzip 字节时为 {@link EncodingGzip}；保持原样时为空串
//   - err      压缩失败错误（理论上不会发生）
//
// 调用方应把 encoding 透传给 apiclient，作为 {@code Content-Encoding} HTTP header 发出。
func maybeCompress(body []byte) (out []byte, encoding string, err error) {
	if len(body) < CompressMinBytes {
		return body, "", nil
	}
	var buf bytes.Buffer
	buf.Grow(len(body) / 4)
	zw, err := gzip.NewWriterLevel(&buf, gzip.BestSpeed)
	if err != nil {
		return body, "", err
	}
	if _, err := zw.Write(body); err != nil {
		_ = zw.Close()
		return body, "", err
	}
	if err := zw.Close(); err != nil {
		return body, "", err
	}
	return buf.Bytes(), EncodingGzip, nil
}

// IsGzip 用 magic bytes 0x1f 0x8b 判断字节是否 gzip 流（用于 outbox 重发时还原 Content-Encoding）。
func IsGzip(b []byte) bool {
	return len(b) >= 2 && b[0] == 0x1f && b[1] == 0x8b
}

// gunzip 仅用于本地工具 / 测试场景；agent 主流程不走这里（解压在 server 端）。
func gunzip(b []byte) ([]byte, error) {
	zr, err := gzip.NewReader(bytes.NewReader(b))
	if err != nil {
		return nil, err
	}
	defer func() { _ = zr.Close() }()
	return io.ReadAll(zr)
}
