package com.yuerong.diligence.common;

public class Fault extends RuntimeException {
  public final String code;
  public final int status;

  public Fault(String code, String message) {
    this(code, message, 400);
  }

  public Fault(String code, String message, int status) {
    this(code, message, status, null);
  }

  public Fault(String code, String message, int status, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.status = status;
  }
}
