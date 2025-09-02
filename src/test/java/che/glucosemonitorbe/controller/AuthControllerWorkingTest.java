package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuthControllerWorkingTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("security.jwt.secret", () -> "test-secret-key-for-integration-tests-only-this-must-be-at-least-512-bits-long-for-hs512-algorithm-to-work-properly-and-securely");
        registry.add("security.jwt.access-expiration-ms", () -> "3600000");
        registry.add("security.jwt.refresh-expiration-ms", () -> "86400000");
    }

    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private TestRestTemplate testRestTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Should successfully register a new user")
    void shouldRegisterNewUserSuccessfully() {
        // Given
        RegisterRequest request = createValidRegisterRequest();
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(request), 
            String.class
        );
        
        // Then
        System.out.println("Response Status: " + response.getStatusCode());
        System.out.println("Response Body: " + response.getBody());
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        // Verify user was created in database
        assertTrue(userRepository.existsByUsername("testuser"));
    }

    @Test
    @DisplayName("Should fail to register user with duplicate username")
    void shouldFailToRegisterUserWithDuplicateUsername() {
        // Given - create first user
        RegisterRequest firstUser = createValidRegisterRequest();
        testRestTemplate.postForEntity("/api/auth/register", createHttpEntity(firstUser), AuthResponse.class);
        
        // When - try to create user with same username
        RegisterRequest duplicateUser = createValidRegisterRequest();
        duplicateUser.setEmail("different@example.com");
        
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(duplicateUser), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Username already exists"));
    }

    @Test
    @DisplayName("Should fail to register user with invalid email")
    void shouldFailToRegisterUserWithInvalidEmail() {
        // Given
        RegisterRequest request = createValidRegisterRequest();
        request.setEmail("invalid-email");
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(request), 
            String.class
        );
        
        // Then
        System.out.println("Invalid Email Response Status: " + response.getStatusCode());
        System.out.println("Invalid Email Response Body: " + response.getBody());
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Email should be valid"));
    }

    @Test
    @DisplayName("Should fail to register user with short password")
    void shouldFailToRegisterUserWithShortPassword() {
        // Given
        RegisterRequest request = createValidRegisterRequest();
        request.setPassword("123");
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(request), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Password must be at least 6 characters"));
    }

    @Test
    @DisplayName("Should fail to register user with missing required fields")
    void shouldFailToRegisterUserWithMissingFields() {
        // Given
        RegisterRequest request = new RegisterRequest();
        // Leave all fields null/empty
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(request), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        String responseBody = response.getBody();
        assertTrue(responseBody.contains("Username is required"));
        assertTrue(responseBody.contains("Email is required"));
        assertTrue(responseBody.contains("Full name is required"));
        assertTrue(responseBody.contains("Password is required"));
    }

    private RegisterRequest createValidRegisterRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        request.setEmail("test@example.com");
        request.setFullName("Test User");
        request.setPassword("testpass123");
        return request;
    }

    private HttpEntity<RegisterRequest> createHttpEntity(RegisterRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(request, headers);
    }
}
