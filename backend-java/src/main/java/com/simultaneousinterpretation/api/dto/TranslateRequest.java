package com.simultaneousinterpretation.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TranslateRequest {

  @NotBlank
  @Size(max = 8000)
  private String segment;

  private String sourceLang = "auto";

  private String targetLang = "zh";

  private boolean kbEnabled = true;

  @Size(max = 2000)
  private String keywordsText = "";

  @Size(max = 2000)
  private String contextText = "";

  @Size(max = 520_000)
  private String meetingMaterialsText = "";

  private List<ImagePayload> images = new ArrayList<>();

  public String getSegment() {
    return segment;
  }

  public void setSegment(String segment) {
    this.segment = segment;
  }

  public String getSourceLang() {
    return sourceLang;
  }

  public void setSourceLang(String sourceLang) {
    this.sourceLang = sourceLang;
  }

  public String getTargetLang() {
    return targetLang;
  }

  public void setTargetLang(String targetLang) {
    this.targetLang = targetLang;
  }

  public boolean isKbEnabled() {
    return kbEnabled;
  }

  public void setKbEnabled(boolean kbEnabled) {
    this.kbEnabled = kbEnabled;
  }

  public String getKeywordsText() {
    return keywordsText;
  }

  public void setKeywordsText(String keywordsText) {
    this.keywordsText = keywordsText;
  }

  public String getContextText() {
    return contextText;
  }

  public void setContextText(String contextText) {
    this.contextText = contextText;
  }

  public String getMeetingMaterialsText() {
    return meetingMaterialsText;
  }

  public void setMeetingMaterialsText(String meetingMaterialsText) {
    this.meetingMaterialsText = meetingMaterialsText;
  }

  public List<ImagePayload> getImages() {
    return images;
  }

  public void setImages(List<ImagePayload> images) {
    this.images = images != null ? images : new ArrayList<>();
  }
}
