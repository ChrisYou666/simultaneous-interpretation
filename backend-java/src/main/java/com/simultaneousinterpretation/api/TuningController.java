package com.simultaneousinterpretation.api;

import com.simultaneousinterpretation.config.PipelineTuningParams;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流水线调参 REST API：查询 / 动态更新运行时参数。
 *
 * <p>所有修改立即生效，无需重启后端。
 * <p>GET /api/tuning/params — 查当前参数快照
 * <p>POST /api/tuning/params — 动态更新参数
 */
@RestController
@RequestMapping("/api/tuning")
public class TuningController {

  private final PipelineTuningParams params;

  public TuningController(PipelineTuningParams params) {
    this.params = params;
  }

  /** 查询所有可调参数当前值 */
  @GetMapping("/params")
  public Map<String, Object> getParams() {
    return params.snapshot();
  }

  /**
   * 动态更新参数。
   *
   * <p>请求体示例：
   * <pre>{@code
   * {
   *   "segMaxChars": 30,
   *   "segFlushTimeoutMs": 500
   * }
   * }</pre>
   *
   * @return {"applied": ["segMaxChars", "segFlushTimeoutMs"], "current": {...snapshot...}}
   */
  @PostMapping("/params")
  public Map<String, Object> updateParams(@RequestBody Map<String, Object> updates) {
    List<String> applied = params.apply(updates);
    return Map.of(
        "applied", applied,
        "current", params.snapshot()
    );
  }
}
