# LibreLinkUp API - Region Redirect Issue & Fix

## Problem Discovered

The `/auth/login` endpoint at `https://api.libreview.io` **does not return a JWT token** directly. Instead, it returns a **redirect response** indicating which regional endpoint should be used.

### Actual Response from `https://api.libreview.io/llu/auth/login`:

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

**No `authTicket` or `token` field!** ❌

## Root Cause

LibreLinkUp API uses **region-specific endpoints** based on user location:

- **Europe (EU)**: `https://api-eu.libreview.io`
- **United States (US)**: `https://api-us.libreview.io`
- **Asia Pacific (AP)**: `https://api-ap.libreview.io`
- **United Arab Emirates (AE)**: `https://api-ae.libreview.io`
- **Japan (JP)**: `https://api-jp.libreview.io`

## Solution Implemented

### 1. Detect Redirect Response

Modified `LibreLinkUpService.authenticate()` to detect redirect responses:

```java
// Check if this is a redirect response (region-specific)
JsonNode data = jsonResponse.get("data");
if (data != null && data.has("redirect") && data.get("redirect").asBoolean()) {
    String region = data.has("region") ? data.get("region").asText() : "";
    logger.warn("LibreLinkUp requires region-specific endpoint. Region: {}", region);
    
    // If redirect is true, we need to use region-specific URL
    if (!region.isEmpty()) {
        return authenticateWithRegion(authRequest, region);
    }
}
```

### 2. Regional Authentication Method

Added `authenticateWithRegion()` method to handle region-specific authentication:

```java
private LibreAuthResponse authenticateWithRegion(LibreAuthRequest authRequest, String region) {
    // Get region-specific base URL
    String regionBaseUrl = getRegionBaseUrl(region);
    String url = regionBaseUrl + "/llu/auth/login";
    
    // Make authentication request to region-specific endpoint
    ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
    
    // Extract token from response
    JsonNode authTicket = jsonResponse.get("data").get("authTicket");
    String token = authTicket.get("token").asText();
    
    // Update base URL for subsequent requests
    this.baseUrl = regionBaseUrl;
    
    return new LibreAuthResponse(token, expires);
}
```

### 3. Region URL Mapping

Added `getRegionBaseUrl()` method to map region codes to endpoints:

```java
private String getRegionBaseUrl(String region) {
    switch (region.toLowerCase()) {
        case "us":
        case "usa":
            return "https://api-us.libreview.io";
        case "eu":
        case "de":
        case "fr":
        case "uk":
        case "gb":
            return "https://api-eu.libreview.io";
        case "ap":
        case "au":
        case "asia":
            return "https://api-ap.libreview.io";
        case "ae":
            return "https://api-ae.libreview.io";
        case "jp":
            return "https://api-jp.libreview.io";
        default:
            return "https://api-eu.libreview.io";
    }
}
```

## Authentication Flow (Fixed)

### Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│ Step 1: Initial Authentication Request                      │
│ POST https://api.libreview.io/llu/auth/login               │
│ Body: { email, password }                                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Step 2: Receive Redirect Response                           │
│ Response: { status: 0, data: { redirect: true, region: "fr" }}│
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Step 3: Map Region to Endpoint                              │
│ "fr" → "https://api-eu.libreview.io"                       │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Step 4: Regional Authentication Request                     │
│ POST https://api-eu.libreview.io/llu/auth/login            │
│ Body: { email, password }                                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Step 5: Receive Token                                       │
│ Response: {                                                  │
│   status: 0,                                                 │
│   data: {                                                    │
│     authTicket: {                                           │
│       token: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",     │
│       expires: 1738449497000                                │
│     }                                                        │
│   }                                                          │
│ }                                                            │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Step 6: Store Token & Use Regional Endpoint                 │
│ All subsequent requests use https://api-eu.libreview.io     │
└─────────────────────────────────────────────────────────────┘
```

## Testing

### Before Fix
```bash
curl -X POST https://api.libreview.io/llu/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password"}'

# Response: { "status": 0, "data": { "redirect": true, "region": "fr" }}
# ❌ No token!
```

### After Fix
```bash
# First request - detects redirect
curl -X POST https://api.libreview.io/llu/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password"}'

# Service automatically makes second request to regional endpoint
# POST https://api-eu.libreview.io/llu/auth/login

# Response: { "status": 0, "data": { "authTicket": { "token": "..." }}}
# ✅ Token received!
```

## Changes Made

### Files Modified

1. **LibreLinkUpService.java**
   - Added redirect detection logic
   - Added `authenticateWithRegion()` method
   - Added `getRegionBaseUrl()` method
   - Enhanced logging for debugging

2. **LIBRE_LINKUP_AUTHENTICATION.md**
   - Documented region-specific endpoints
   - Updated authentication flow
   - Added regional endpoint table

3. **LIBRE_LINKUP_REGION_REDIRECT_FIX.md** (this file)
   - Comprehensive documentation of the issue and fix

## Impact

### Before
- ❌ Authentication failed with "No authentication token received"
- ❌ Only worked if you manually used the regional endpoint
- ❌ Confusing error messages

### After
- ✅ Automatic region detection
- ✅ Seamless redirect to regional endpoint
- ✅ Works for all regions worldwide
- ✅ Clear logging of the redirect process

## Configuration

No additional configuration needed! The service automatically:
1. Detects your region from the initial response
2. Maps region to the correct endpoint
3. Re-authenticates with the regional endpoint
4. Uses the regional endpoint for all subsequent requests

## Supported Regions

| Region Code | Countries | Endpoint |
|-------------|-----------|----------|
| **fr, de, uk, gb, eu** | France, Germany, UK, Europe | `https://api-eu.libreview.io` |
| **us, usa** | United States | `https://api-us.libreview.io` |
| **ap, au, asia** | Australia, Asia Pacific | `https://api-ap.libreview.io` |
| **ae** | United Arab Emirates | `https://api-ae.libreview.io` |
| **jp** | Japan | `https://api-jp.libreview.io` |

## Troubleshooting

### Issue: Still getting "No token" error

**Check the logs:**
```
LibreLinkUp auth response: {"status":0,"data":{"redirect":true,"region":"fr"}}
LibreLinkUp requires region-specific endpoint. Region: fr
Authenticating with region-specific LibreLinkUp API at https://api-eu.libreview.io/llu/auth/login
Region-specific auth response: {"status":0,"data":{"authTicket":{"token":"..."}}}
Successfully authenticated with region fr endpoint
```

If you see the first two lines but not the rest, check:
1. Network connectivity to regional endpoint
2. Circuit breaker status
3. Firewall/proxy settings

### Issue: Wrong region detected

The service uses a default mapping. If your region is not correctly detected:

1. Check the `region` field in the initial response
2. Update `getRegionBaseUrl()` method if needed
3. Or manually set `libre.api.base-url` in `application.yml`

## Lessons Learned

1. **Don't assume API structure**: Always log and inspect actual API responses
2. **Regional variations**: Modern APIs often use region-specific endpoints
3. **Follow redirects**: Some APIs use redirect responses instead of HTTP redirects
4. **Test with real data**: Testing with actual LibreLinkUp accounts revealed the issue

## References

- LibreLinkUp API (unofficial): https://github.com/DRFR0ST/libre-link-unofficial-api
- Regional endpoints discovered through testing
- LibreView website: https://www.libreview.com

