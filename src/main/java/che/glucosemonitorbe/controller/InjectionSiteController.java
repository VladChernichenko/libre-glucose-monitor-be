package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.InjectionSitesDTO;
import che.glucosemonitorbe.service.InjectionSiteService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Injection-site rotation for the body map (spec F7). Sites themselves are set on notes (siteZone). */
@Tag(name = "Injection sites", description = "Site rotation: last use per zone, suggestion, repeat warning")
@RestController
@RequestMapping("/api/injection-sites")
@RequiredArgsConstructor
public class InjectionSiteController {

    private final InjectionSiteService injectionSiteService;
    private final UserService userService;

    @Operation(summary = "Zones with last use and colour band, the suggested next zone, and any repeat warning")
    @GetMapping
    public ResponseEntity<InjectionSitesDTO> summary(Authentication auth,
            @RequestParam(defaultValue = "rapid") String insulin) {
        boolean rapid = switch (insulin.toLowerCase()) {
            case "rapid" -> true;
            case "basal" -> false;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insulin must be rapid or basal");
        };
        return ResponseEntity.ok(injectionSiteService.summary(
                userService.getUserByUsername(auth.getName()).getId(), rapid));
    }
}
