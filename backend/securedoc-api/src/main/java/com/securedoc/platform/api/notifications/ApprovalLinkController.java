package com.securedoc.platform.api.notifications;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.notifications.DeliveryApprovals.Answer;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 0059: the approval page's two calls. Both are POSTs with the token in the body, so a token is in no server's address log
 * and no GET does anything; both need a signed-in Supervisor of an entity that is not suspended. Only {@code decide} decides.
 */
@NotificationsOn
@RestController
@RequestMapping("/api/v1/approvals/link")
public class ApprovalLinkController {

    private final RequestContextResolver context;
    private final DeliveryApprovals approvals;

    public ApprovalLinkController(RequestContextResolver context, DeliveryApprovals approvals) {
        this.context = context;
        this.approvals = approvals;
    }

    public record OpenRequest(String token) {
        @Override
        public String toString() {
            return "OpenRequest[]";
        }
    }

    public record DecideRequest(String token, String reason) {
        @Override
        public String toString() {
            return "DecideRequest[]";
        }
    }

    @PostMapping("/open")
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<Answer> open(@RequestBody OpenRequest body, HttpServletRequest request) {
        return answer(approvals.open(context.resolvePrincipal(request), body.token()));
    }

    @PostMapping("/decide")
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<Answer> decide(@RequestBody DecideRequest body, HttpServletRequest request) {
        return answer(approvals.decide(context.resolvePrincipal(request), context.resolveCorrelationId(request), body.token(),
                body.reason()));
    }

    private static ResponseEntity<Answer> answer(Answer answer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(answer);
    }
}
