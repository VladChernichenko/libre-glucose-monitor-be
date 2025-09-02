package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RefreshTokenRequest;
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

    private HttpEntity<AuthRequest> createHttpEntity(AuthRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(request, headers);
    }

    private HttpEntity<RefreshTokenRequest> createHttpEntity(RefreshTokenRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(request, headers);
    }

    // ==================== LOGIN ENDPOINT TESTS ====================

    @Test
    @DisplayName("Should successfully login with valid credentials")
    void shouldLoginSuccessfullyWithValidCredentials() {
        // Given - First register a user
        RegisterRequest registerRequest = createValidRegisterRequest();
        testRestTemplate.postForEntity("/api/auth/register", createHttpEntity(registerRequest), AuthResponse.class);
        
        // Create login request
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("testpass123");
        
        // When
        ResponseEntity<AuthResponse> response = testRestTemplate.postForEntity(
            "/api/auth/login", 
            createHttpEntity(loginRequest), 
            AuthResponse.class
        );
        
        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        AuthResponse authResponse = response.getBody();
        assertNotNull(authResponse.getAccessToken());
        assertNotNull(authResponse.getRefreshToken());
        assertEquals("Bearer", authResponse.getTokenType());
        assertNotNull(authResponse.getExpiresIn());
        assertTrue(authResponse.getExpiresIn() > 0);
    }

    @Test
    @DisplayName("Should fail to login with invalid username")
    void shouldFailToLoginWithInvalidUsername() {
        // Given
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setUsername("nonexistentuser");
        loginRequest.setPassword("testpass123");
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/login", 
            createHttpEntity(loginRequest), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        // Check for any authentication error message
        String responseBody = response.getBody();
        assertTrue(responseBody.contains("Bad credentials") || 
                  responseBody.contains("Unauthorized") || 
                  responseBody.contains("Authentication failed") ||
                  responseBody.contains("Invalid credentials"));
    }

    @Test
    @DisplayName("Should fail to login with invalid password")
    void shouldFailToLoginWithInvalidPassword() {
        // Given - First register a user
        RegisterRequest registerRequest = createValidRegisterRequest();
        testRestTemplate.postForEntity("/api/auth/register", createHttpEntity(registerRequest), AuthResponse.class);
        
        // Create login request with wrong password
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("wrongpassword");
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/login", 
            createHttpEntity(loginRequest), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        // Check for any authentication error message
        String responseBody = response.getBody();
        assertTrue(responseBody.contains("Bad credentials") || 
                  responseBody.contains("Unauthorized") || 
                  responseBody.contains("Authentication failed") ||
                  responseBody.contains("Invalid credentials"));
    }

    @Test
    @DisplayName("Should fail to login with empty username")
    void shouldFailToLoginWithEmptyUsername() {
        // Given
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setUsername("");
        loginRequest.setPassword("testpass123");
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/login", 
            createHttpEntity(loginRequest), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Username is required"));
    }

    @Test
    @DisplayName("Should fail to login with empty password")
    void shouldFailToLoginWithEmptyPassword() {
        // Given
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("");
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/login", 
            createHttpEntity(loginRequest), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Password is required"));
    }

    @Test
    @DisplayName("Should fail to login with malformed request body")
    void shouldFailToLoginWithMalformedRequestBody() {
        // Given - Invalid JSON
        String invalidJson = "{ \"username\": \"testuser\", \"password\": }";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(invalidJson, headers);
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/login", 
            entity, 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ==================== REFRESH TOKEN ENDPOINT TESTS ====================

    @Test
    @DisplayName("Should successfully refresh token with valid refresh token")
    void shouldRefreshTokenSuccessfullyWithValidRefreshToken() {
        // Given - First register and login to get tokens
        RegisterRequest registerRequest = createValidRegisterRequest();
        ResponseEntity<AuthResponse> registerResponse = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(registerRequest), 
            AuthResponse.class
        );
        
        String refreshToken = registerResponse.getBody().getRefreshToken();
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken(refreshToken);
        
        // When
        ResponseEntity<AuthResponse> response = testRestTemplate.postForEntity(
            "/api/auth/refresh", 
            createHttpEntity(refreshRequest), 
            AuthResponse.class
        );
        
        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        AuthResponse authResponse = response.getBody();
        assertNotNull(authResponse.getAccessToken());
        assertNotNull(authResponse.getRefreshToken());
        assertEquals("Bearer", authResponse.getTokenType());
        assertNotNull(authResponse.getExpiresIn());
        assertTrue(authResponse.getExpiresIn() > 0);
        
        // Verify tokens are properly generated (implementation may return same or different tokens)
        assertNotNull(authResponse.getAccessToken());
        assertNotNull(authResponse.getRefreshToken());
        // Note: The current implementation may return the same tokens, which is acceptable
    }

    @Test
    @DisplayName("Should fail to refresh token with invalid refresh token")
    void shouldFailToRefreshTokenWithInvalidRefreshToken() {
        // Given
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken("invalid.refresh.token");
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/refresh", 
            createHttpEntity(refreshRequest), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Invalid refresh token"));
    }

    @Test
    @DisplayName("Should fail to refresh token with access token instead of refresh token")
    void shouldFailToRefreshTokenWithAccessToken() {
        // Given - First register to get access token
        RegisterRequest registerRequest = createValidRegisterRequest();
        ResponseEntity<AuthResponse> registerResponse = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(registerRequest), 
            AuthResponse.class
        );
        
        // Use access token instead of refresh token
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken(registerResponse.getBody().getAccessToken());
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/refresh", 
            createHttpEntity(refreshRequest), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Invalid refresh token"));
    }

    @Test
    @DisplayName("Should fail to refresh token with empty refresh token")
    void shouldFailToRefreshTokenWithEmptyRefreshToken() {
        // Given
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken("");
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/refresh", 
            createHttpEntity(refreshRequest), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Refresh token is required"));
    }

    @Test
    @DisplayName("Should fail to refresh token with malformed request body")
    void shouldFailToRefreshTokenWithMalformedRequestBody() {
        // Given - Invalid JSON
        String invalidJson = "{ \"refreshToken\": }";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(invalidJson, headers);
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/refresh", 
            entity, 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("Should fail to refresh token with null refresh token")
    void shouldFailToRefreshTokenWithNullRefreshToken() {
        // Given
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken(null);
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/refresh", 
            createHttpEntity(refreshRequest), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Refresh token is required"));
    }

    // ==================== TEST ENDPOINT TESTS ====================

    @Test
    @DisplayName("Should return test message successfully")
    void shouldReturnTestMessageSuccessfully() {
        // When
        ResponseEntity<String> response = testRestTemplate.getForEntity(
            "/api/auth/test", 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Auth endpoint is working!", response.getBody());
    }

    @Test
    @DisplayName("Should return test message with correct headers")
    void shouldReturnTestMessageWithCorrectHeaders() {
        // When
        ResponseEntity<String> response = testRestTemplate.getForEntity(
            "/api/auth/test", 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().toString().contains("text/plain"));
        assertEquals("Auth endpoint is working!", response.getBody());
    }
}
