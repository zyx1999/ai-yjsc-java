package com.yuerong.diligence.model;

/** Application download result; HTTP headers belong to the controller. */
public final class FileDownload {
  public final String name, contentType;
  public final byte[] content;
  public FileDownload(String name, String contentType, byte[] content) {
    this.name = name; this.contentType = contentType; this.content = content;
  }
}
