package com.simultaneousinterpretation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 虚拟音频输出设备配置。
 *
 * device-zh/en/id 填写设备名称的关键字（不区分大小写，子串匹配）。
 * 系统会在所有 javax.sound.sampled Mixer 里找第一个包含该关键字的播放设备。
 *
 * VoiceMeeter Potato 方案（完全免费）：
 *   device-zh: "VoiceMeeter Input"       (B1)
 *   device-id: "VoiceMeeter Aux Input"   (B2)
 */
@Component
@ConfigurationProperties(prefix = "app.audio-output")
public class AudioOutputProperties {

    private String deviceZh = "";
    private String deviceId = "";

    public String getDeviceZh() { return deviceZh; }
    public void setDeviceZh(String deviceZh) { this.deviceZh = deviceZh; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDevice(String lang) {
        return switch (lang.toLowerCase()) {
            case "zh" -> deviceZh;
            default   -> deviceId;
        };
    }
}
