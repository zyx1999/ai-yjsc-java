package com.yuerong.diligence.web.security;

import com.yuerong.diligence.common.Fault;

import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Access {
  private final HttpServletRequest request;
  private final String devUser;
  private final boolean trustedHeader;

  public Access(
      HttpServletRequest request,
      @Value("${diligence.security.dev-user:}") String devUser,
      @Value("${diligence.security.trusted-user-header:false}") boolean trustedHeader) {
    this.request = request;
    this.devUser = devUser;
    this.trustedHeader = trustedHeader;
  }

  public String user() {
    String user = request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName();
    if (user == null && trustedHeader) user = request.getHeader("X-User-Id");
    if (user == null && !devUser.isEmpty()) user = devUser;
    if (user == null || !user.matches("[A-Za-z0-9_.@-]{1,64}"))
      throw new Fault("UNAUTHENTICATED", "缺少可信用户身份", 401);
    return user;
  }

}
