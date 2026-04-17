-- 下行 JSON 持久化：断线后按 stream_id + seq 补发（不含大体量 audio.data）
CREATE TABLE IF NOT EXISTS si_asr_outbound_journal (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  stream_id VARCHAR(64) NOT NULL,
  seq BIGINT NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_si_asr_out_stream_seq (stream_id, seq),
  KEY idx_si_asr_out_stream_seq (stream_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
