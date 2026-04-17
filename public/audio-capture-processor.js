/**
 * 音频采集 Worklet（运行在专用音频线程，不阻塞主线程）。
 *
 * 每次 process() 收到固定 128 样本（Web Audio 规范要求），
 * 累积到 chunkSamples（默认 4800 = 100ms @ 48kHz）后
 * 通过 port.postMessage 将 Float32Array 发送给主线程。
 * 主线程再做 Int16 编码、（可选）下采样和 WebSocket 发送。
 *
 * processorOptions:
 *   chunkSamples {number} - 每次发送的样本数（默认 4800）
 */
class AudioCaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this._chunkSamples = options?.processorOptions?.chunkSamples ?? 4800;
    this._buf = [];
    this._bufLen = 0;
  }

  process(inputs) {
    const channel = inputs[0]?.[0];
    if (!channel) return true;

    // 复制一份，避免 Web Audio 引擎在回调结束后回收这块内存
    this._buf.push(channel.slice());
    this._bufLen += channel.length;

    if (this._bufLen >= this._chunkSamples) {
      // 合并所有缓冲帧为一个连续数组
      const out = new Float32Array(this._bufLen);
      let pos = 0;
      for (const c of this._buf) {
        out.set(c, pos);
        pos += c.length;
      }
      // Transferable：将 ArrayBuffer 所有权移交主线程，零拷贝
      this.port.postMessage(out, [out.buffer]);
      this._buf = [];
      this._bufLen = 0;
    }

    return true; // 返回 true 保持处理器存活
  }
}

registerProcessor("audio-capture-processor", AudioCaptureProcessor);
