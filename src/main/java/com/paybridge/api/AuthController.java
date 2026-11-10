package com.paybridge.api;

import com.paybridge.api.ApiDtos.ProfileLoginRequest;
import com.paybridge.api.ApiDtos.SessionResponse;
import com.paybridge.api.ApiDtos.SessionUserView;
import com.paybridge.api.ApiDtos.WorkspaceProfileView;
import com.paybridge.domain.DemoPrincipal;
import com.paybridge.service.DemoLoginRateLimiter;
import com.paybridge.service.WorkspaceProfileCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthController.class);

  private final WorkspaceProfileCatalog profileCatalog;
  private final DemoLoginRateLimiter rateLimiter;
  private final SecurityContextRepository securityContextRepository;

  public AuthController(
      WorkspaceProfileCatalog profileCatalog,
      DemoLoginRateLimiter rateLimiter,
      SecurityContextRepository securityContextRepository) {
    this.profileCatalog = profileCatalog;
    this.rateLimiter = rateLimiter;
    this.securityContextRepository = securityContextRepository;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrfToken(CsrfToken csrfToken) {
    return Map.of("token", csrfToken.getToken());
  }

  @GetMapping("/profiles")
  public Map<String, List<WorkspaceProfileView>> profiles() {
    return Map.of("accounts", profileCatalog.listProfiles());
  }

  @PostMapping("/profile-login")
  public SessionResponse profileLogin(
      @Valid @RequestBody ProfileLoginRequest requestBody,
      HttpServletRequest request,
      HttpServletResponse response) {
    rateLimiter.requireAllowed(request.getRemoteAddr());
    DemoPrincipal principal = profileCatalog.authenticate(requestBody.accountId());
    if (request.getSession(false) != null) {
      request.changeSessionId();
    }
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new UsernamePasswordAuthenticationToken(
        principal,
        null,
        List.of(new SimpleGrantedAuthority(principal.role().authority()))));
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);
    LOGGER.info("eventName=auth.profile_session.started outcome=success");
    return new SessionResponse(toUserView(principal), true);
  }

  @GetMapping("/me")
  public SessionResponse currentUser(@AuthenticationPrincipal DemoPrincipal principal) {
    return new SessionResponse(toUserView(principal), true);
  }

  @PostMapping("/logout")
  public void logout(
      HttpServletRequest request,
      HttpServletResponse response) {
    new SecurityContextLogoutHandler().logout(
        request, response, SecurityContextHolder.getContext().getAuthentication());
    LOGGER.info("eventName=auth.session.ended outcome=success");
  }

  private SessionUserView toUserView(DemoPrincipal principal) {
    return new SessionUserView(principal.id(), principal.displayName(), principal.role(), principal.merchantId());
  }
}
