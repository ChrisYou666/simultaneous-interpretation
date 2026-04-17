-- 同传链路多阶段 API 配置（ASR/MT/TTS/CHAT 等），一条记录对应一个可调用端点；不再由终端用户选择「模型」。
CREATE TABLE IF NOT EXISTS si_api_endpoint (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  stage_code VARCHAR(64) NOT NULL COMMENT '链路阶段：CHAT=OpenAI兼容对话/试译，ASR/TTS/MT 等预留给后续同传管线',
  name VARCHAR(128) NOT NULL,
  base_url VARCHAR(512) NULL,
  api_key MEDIUMTEXT NULL,
  model_id VARCHAR(128) NULL COMMENT '该端点使用的 model 名称，如 gpt-4o-mini',
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_stage_enabled (stage_code, enabled),
  INDEX idx_sort (sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 自旧表迁移：每条 si_model_app 对应一条 CHAT 端点，Base URL / Key 来自 si_setting（各端点共享历史配置）
INSERT INTO si_api_endpoint (stage_code, name, base_url, api_key, model_id, enabled, sort_order)
SELECT
  'CHAT',
  m.name,
  (SELECT s.setting_value FROM si_setting s WHERE s.setting_key = 'openai.base_url' LIMIT 1),
  (SELECT s.setting_value FROM si_setting s WHERE s.setting_key = 'openai.api_key' LIMIT 1),
  m.model_id,
  m.enabled,
  m.sort_order
FROM si_model_app m
ORDER BY m.sort_order ASC, m.id ASC;

INSERT INTO si_api_endpoint (stage_code, name, base_url, api_key, model_id, enabled, sort_order)
SELECT 'CHAT', '默认',
  (SELECT setting_value FROM si_setting WHERE setting_key = 'openai.base_url' LIMIT 1),
  (SELECT setting_value FROM si_setting WHERE setting_key = 'openai.api_key' LIMIT 1),
  'gpt-4o-mini', 1, 0
FROM DUAL
WHERE (SELECT COUNT(*) FROM si_api_endpoint) = 0
  AND EXISTS (
    SELECT 1 FROM si_setting
    WHERE setting_key = 'openai.api_key' AND setting_value IS NOT NULL AND TRIM(setting_value) <> ''
  );
