package com.janne6565.hermes.controller.v1.schema;

import com.janne6565.hermes.model.core.GoogleAccountDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/api/v1/auth/google")
@Tag(name = "Google account", description = "Connect the mailbox with Sign in with Google")
public interface AuthApi {

    @GetMapping("/status")
    @Operation(
            summary = "Whether a mailbox is connected",
            description = "Carries no token material — only the connection state and the address.")
    @ApiResponse(responseCode = "200", description = "Connection state")
    ResponseEntity<GoogleAccountDto> status();

    @GetMapping("/start")
    @Operation(
            summary = "Begin the sign-in flow",
            description =
                    "Returns the Google consent URL to send the browser to. A single-use state "
                            + "parameter is minted here and required on the callback.")
    @ApiResponse(responseCode = "200", description = "Consent URL")
    @ApiResponse(responseCode = "400", description = "No OAuth client configured")
    ResponseEntity<AuthorizationUrl> start();

    @GetMapping("/callback")
    @Operation(
            summary = "Google's redirect target",
            description =
                    "Exchanges the authorization code, stores the refresh token encrypted, and "
                            + "redirects back into the app. Not called directly.")
    @ApiResponse(responseCode = "302", description = "Redirect back to the app")
    ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error);

    @DeleteMapping
    @Operation(
            summary = "Forget the connected account",
            description =
                    "Deletes the stored token. Does not revoke the grant at Google — do that at "
                            + "myaccount.google.com/permissions.")
    @ApiResponse(responseCode = "204", description = "Disconnected")
    ResponseEntity<Void> disconnect();

    /** @param url the Google consent URL the browser should be sent to. */
    record AuthorizationUrl(String url) {}
}
