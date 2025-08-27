package che.glucosemonitorbe.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000"}, allowCredentials = "true")
public class TestController {
    
    @GetMapping("/health")
    public String health() {
        return "Application is running!";
    }
    
    @GetMapping("/status")
    public String status() {
        return "Status: OK - Database connection should be established";
    }
}
