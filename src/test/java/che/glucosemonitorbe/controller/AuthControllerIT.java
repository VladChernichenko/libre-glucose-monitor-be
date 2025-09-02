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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class AuthControllerIT {

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
        ResponseEntity<AuthResponse> response = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(request), 
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
    @DisplayName("Should fail to register user with duplicate email")
    void shouldFailToRegisterUserWithDuplicateEmail() {
        // Given - create first user
        RegisterRequest firstUser = createValidRegisterRequest();
        testRestTemplate.postForEntity("/api/auth/register", createHttpEntity(firstUser), AuthResponse.class);
        
        // When - try to create user with same email
        RegisterRequest duplicateUser = createValidRegisterRequest();
        duplicateUser.setUsername("differentuser");
        
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(duplicateUser), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Email already exists"));
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
    @DisplayName("Should fail to register user with short username")
    void shouldFailToRegisterUserWithShortUsername() {
        // Given
        RegisterRequest request = createValidRegisterRequest();
        request.setUsername("ab");
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(request), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Username must be between 3 and 50 characters"));
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

    @Test
    @DisplayName("Should fail to register user with long username")
    void shouldFailToRegisterUserWithLongUsername() {
        // Given
        RegisterRequest request = createValidRegisterRequest();
        request.setUsername("a".repeat(51)); // 51 characters
        
        // When
        ResponseEntity<String> response = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(request), 
            String.class
        );
        
        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Username must be between 3 and 50 characters"));
    }

    @Test
    @DisplayName("Should register multiple different users successfully")
    void shouldRegisterMultipleDifferentUsersSuccessfully() {
        // Given
        RegisterRequest user1 = createValidRegisterRequest();
        user1.setUsername("user1");
        user1.setEmail("user1@example.com");
        
        RegisterRequest user2 = createValidRegisterRequest();
        user2.setUsername("user2");
        user2.setEmail("user2@example.com");
        user2.setFullName("User Two");
        
        // When
        ResponseEntity<AuthResponse> response1 = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(user1), 
            AuthResponse.class
        );
        
        ResponseEntity<AuthResponse> response2 = testRestTemplate.postForEntity(
            "/api/auth/register", 
            createHttpEntity(user2), 
            AuthResponse.class
        );
        
        // Then
        assertEquals(HttpStatus.OK, response1.getStatusCode());
        assertEquals(HttpStatus.OK, response2.getStatusCode());
        
        assertNotNull(response1.getBody().getAccessToken());
        assertNotNull(response2.getBody().getAccessToken());
        
        // Verify both users were created
        assertTrue(userRepository.existsByUsername("user1"));
        assertTrue(userRepository.existsByUsername("user2"));
        assertEquals(2, userRepository.count());
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