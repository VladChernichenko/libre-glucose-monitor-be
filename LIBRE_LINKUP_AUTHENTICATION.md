# LibreLinkUp API Authentication - Tokens and Keys

## Overview

The LibreLinkUp API uses **Bearer Token authentication** (JWT-based). This document describes all the tokens, keys, and credentials needed to integrate with the LibreLinkUp API.

## Required Credentials

### 1. **User Credentials** (Required)
These are the user's LibreLinkUp account credentials:

- **Email**: LibreLinkUp account email address
- **Password**: LibreLinkUp account password

```json
{
  "email": "user@example.com",
  "password": "your-password"
}
```

### 2. **Authentication Token** (Obtained from API)
After successful authentication, the API returns:

- **Token**: JWT Bearer token for API authentication
- **Expires**: Token expiration timestamp (milliseconds since epoch)

```json
{
  "authTicket": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "expires": 1738449497000,
    "duration": 86400000
  }
}
```

### 3. **Patient ID** (Obtained from API)
After authentication, you need to fetch connections to get:

- **Patient ID**: UUID of the patient/sensor connection
- **Connection Status**: active/inactive/pending

```json
{
  "patientId": "2b2b2b2b-2b2b-2b2b-2b2b-2b2b2b2b2b2b",
  "firstName": "John",
  "lastName": "Doe",
  "status": "active"
}
```

## Authentication Flow

### Important: Region-Specific Endpoints

LibreLinkUp API uses **region-specific endpoints**. The initial login to `https://api.libreview.io` will return a redirect response with your region:

**Initial Request:**
```http
POST https://api.libreview.io/llu/auth/login
Content-Type: application/json
Product: llu.ios
Version: 4.9.0

{
  "email": "user@example.com",
  "password": "your-password"
}
```

**Redirect Response:**
```json
{
  "status": 0,
  "data": {
    "country": "FR",
    "redirect": true,
    "region": "fr",
    "uilanguage": "en-GB"
  }
}
```

### Step 1: Login to Region-Specific Endpoint

After receiving the redirect response, authenticate with the region-specific endpoint:

```http
POST https://api-eu.libreview.io/llu/auth/login
Content-Type: application/json
Product: llu.ios
Version: 4.9.0

{
  "email": "user@example.com",
  "password": "your-password"
}
```

**Response:**
```json
{
  "status": 0,
  "data": {
    "user": {
      "id": "user-uuid",
      "email": "user@example.com",
      "firstName": "John",
      "lastName": "Doe"
    },
    "authTicket": {
      "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
      "expires": 1738449497000,
      "duration": 86400000
    }
  }
}
```

### Regional Endpoints

| Region | Countries | Base URL |
|--------|-----------|----------|
| **EU** | France (FR), Germany (DE), UK (GB), etc. | `https://api-eu.libreview.io` |
| **US** | United States (US) | `https://api-us.libreview.io` |
| **AP** | Australia (AU), Asia Pacific | `https://api-ap.libreview.io` |
| **AE** | United Arab Emirates | `https://api-ae.libreview.io` |
| **JP** | Japan | `https://api-jp.libreview.io` |

### Step 2: Get Patient Connections
```http
GET https://api.libreview.io/llu/connections
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Product: llu.ios
Version: 4.9.0
```

**Response:**
```json
{
  "status": 0,
  "data": [
    {
      "patientId": "2b2b2b2b-2b2b-2b2b-2b2b-2b2b2b2b2b2b",
      "firstName": "John",
      "lastName": "Doe",
      "status": "active",
      "lastSync": "2025-10-01T15:30:00Z"
    }
  ]
}
```

### Step 3: Fetch Glucose Data
```http
GET https://api.libreview.io/llu/connections/{patientId}/graph
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Product: llu.ios
Version: 4.9.0
```

## Token Storage and Management

### Backend (LibreLinkUpService.java)

```java
// Store token in memory
private String authToken;

// Set token after authentication
public LibreAuthResponse authenticate(LibreAuthRequest authRequest) {
    // ... authentication logic ...
    this.authToken = token;
    return new LibreAuthResponse(token, expires);
}

// Use token in subsequent requests
HttpHeaders headers = new HttpHeaders();
headers.set("Authorization", "Bearer " + authToken);
```

### Frontend (libreApi.ts)

```typescript
// Store token in localStorage
private token: string | null = null;

async authenticate(email: string, password: string) {
    const response = await this.api.post('/api/libre/auth/login', {
        email,
        password
    });
    
    // Store token
    this.token = response.data.token;
    localStorage.setItem('libre_token', this.token);
}

// Use token in requests
this.api.interceptors.request.use((config) => {
    const token = authService.getAccessToken(); // Your app's JWT token
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});
```

## Important Security Considerations

### 1. **Never Expose Tokens**
❌ **DON'T**:
```javascript
console.log('Token:', token); // Never log tokens
localStorage.setItem('token', token); // Vulnerable to XSS
```

✅ **DO**:
```javascript
// Use secure storage
sessionStorage.setItem('libre_token', token);
// Or use httpOnly cookies
```

### 2. **Token Expiration**
Tokens typically expire after **24 hours**. Implement token refresh:

```java
// Check token expiration
if (System.currentTimeMillis() > tokenExpires) {
    // Re-authenticate
    authenticate(authRequest);
}
```

### 3. **Secure Credential Storage**
❌ **DON'T**:
```java
// Never hardcode credentials
String email = "user@example.com";
String password = "password123";
```

✅ **DO**:
```yaml
# Use environment variables
libre:
  credentials:
    email: ${LIBRE_EMAIL}
    password: ${LIBRE_PASSWORD}
```

## Required HTTP Headers

All LibreLinkUp API requests require these headers:

### Authentication Request
```http
Content-Type: application/json
Accept: application/json
Product: llu.ios
Version: 4.9.0
User-Agent: LibreLinkUp/4.9.0 (iOS)
```

### Authenticated Requests
```http
Authorization: Bearer {token}
Accept: application/json
Product: llu.ios
Version: 4.9.0
User-Agent: LibreLinkUp/4.9.0 (iOS)
```

## Environment Variables Setup

### Backend (.env or application.yml)
```yaml
libre:
  api:
    base-url: https://api.libreview.io
    client-version: 4.9.0
  credentials:
    # These should be stored securely (encrypted or in secrets manager)
    email: ${LIBRE_EMAIL:}
    password: ${LIBRE_PASSWORD:}
```

### Frontend (.env)
```bash
REACT_APP_LIBRE_EMAIL=
REACT_APP_LIBRE_PASSWORD=
REACT_APP_BACKEND_URL=http://localhost:8080
```

## API Keys NOT Required

**Important**: LibreLinkUp API does **NOT** require:
- ❌ API Keys
- ❌ Client ID/Secret
- ❌ OAuth 2.0 credentials
- ❌ Webhook secrets

It only uses:
- ✅ User email and password
- ✅ JWT Bearer tokens (obtained from login)

## Token Lifecycle

```
1. User Login
   ↓
2. Receive JWT Token (expires in 24h)
   ↓
3. Store Token Securely
   ↓
4. Use Token for API Requests
   ↓
5. Monitor Token Expiration
   ↓
6. Refresh/Re-authenticate when expired
```

## Complete Authentication Example

### Backend Java
```java
@Service
public class LibreLinkUpService {
    private String authToken;
    private Long tokenExpires;
    
    public LibreAuthResponse authenticate(LibreAuthRequest request) {
        // Call LibreLinkUp API
        ResponseEntity<String> response = restTemplate.exchange(
            "https://api.libreview.io/llu/auth/login",
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            String.class
        );
        
        // Extract token from response
        JsonNode authTicket = jsonResponse.get("authTicket");
        String token = authTicket.get("token").asText();
        Long expires = authTicket.get("expires").asLong();
        
        // Store token
        this.authToken = token;
        this.tokenExpires = expires;
        
        return new LibreAuthResponse(token, expires);
    }
    
    public LibreGlucoseData getGlucoseData(String patientId) {
        // Check token validity
        if (authToken == null || System.currentTimeMillis() > tokenExpires) {
            throw new RuntimeException("Not authenticated");
        }
        
        // Use token in request
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        
        // Make API call...
    }
}
```

### Frontend TypeScript
```typescript
class LibreApiService {
    private token: string | null = null;
    
    async authenticate(email: string, password: string) {
        const response = await axios.post(
            `${this.backendUrl}/api/libre/auth/login`,
            { email, password }
        );
        
        // Store token
        this.token = response.data.token;
        localStorage.setItem('libre_token', this.token);
        
        return response.data;
    }
    
    async getGlucoseData(patientId: string) {
        // Token is automatically added by interceptor
        const response = await this.api.get(
            `/api/libre/connections/${patientId}/graph`
        );
        
        return response.data;
    }
}
```

## Troubleshooting

### Token Issues

1. **"Not authenticated" error**
   - Check if token is stored correctly
   - Verify token hasn't expired
   - Re-authenticate if needed

2. **"Invalid token" error**
   - Token format is incorrect
   - Token has been revoked
   - Get a new token

3. **"Token expired" error**
   - Token has exceeded 24-hour lifetime
   - Re-authenticate with credentials

## Summary

### What You Need:
1. ✅ **LibreLinkUp Account** (email + password)
2. ✅ **JWT Bearer Token** (obtained from `/llu/auth/login`)
3. ✅ **Patient ID** (obtained from `/llu/connections`)

### What You DON'T Need:
- ❌ API Keys
- ❌ Client Secrets
- ❌ OAuth Credentials

### Security Best Practices:
- 🔒 Store credentials in environment variables
- 🔒 Never log tokens or passwords
- 🔒 Use HTTPS for all API calls
- 🔒 Implement token refresh logic
- 🔒 Monitor token expiration
- 🔒 Use secure storage (httpOnly cookies or sessionStorage)

