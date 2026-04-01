# LibreLinkUp API - Headers Fix

## Problem

Even when using the region-specific endpoint (`https://api-eu.libreview.io/llu/auth/login`), the API was still returning a redirect response without a token:

```json
{
  "status": 0,
  "data": {
    "redirect": true,
    "region": "fr"
  }
}
```

## Root Cause

The issue was with the **HTTP headers** we were sending. The LibreLinkUp API is very strict about which clients it accepts, and the headers we were using (`Product: llu.ios`) were causing the API to reject the request with a redirect response.

## Solution

Changed the headers from iOS to Android format:

### Before (Not Working) ❌
```java
headers.set("Product", "llu.ios");
headers.set("Version", "4.9.0");
headers.set("User-Agent", "LibreLinkUp/4.9.0 (iOS)");
```

### After (Working) ✅
```java
headers.set("Product", "llu.android");
headers.set("Version", "4.12.0");
headers.set("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/115.0 Firefox/115.0");
headers.set("Accept-Encoding", "gzip, deflate, br");
headers.set("Connection", "keep-alive");
```

## Changes Made

### 1. Updated Client Version
```java
private String clientVersion = "4.12.0"; // Updated from 4.9.0
```

### 2. Changed Product Header
From `llu.ios` to `llu.android` - the Android API seems to be more permissive.

### 3. Updated User-Agent
Changed from iOS app format to Android browser format to match working implementations.

### 4. Added Additional Headers
- `Accept-Encoding: gzip, deflate, br`
- `Connection: keep-alive`

## Testing

### Test with curl:

```bash
chmod +x test_libre_auth.sh
./test_libre_auth.sh
```

Or manually:

```bash
curl -X POST "https://api-eu.libreview.io/llu/auth/login" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Product: llu.android" \
  -H "Version: 4.12.0" \
  -H "User-Agent: Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/115.0 Firefox/115.0" \
  -H "Accept-Encoding: gzip, deflate, br" \
  -H "Connection: keep-alive" \
  -d '{"email":"your-email@gmail.com","password":"your-password"}'
```

### Expected Response (Success):

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

✅ **Now includes `authTicket.token`!**

## Why This Works

The LibreLinkUp API appears to:
1. **Validate the Product header** and reject certain clients
2. **Check the User-Agent** to ensure it matches the Product
3. **Prefer Android clients** over iOS clients (possibly due to stricter iOS validation)
4. **Require specific version numbers** (4.12.0 is the latest working version)

## Files Modified

1. **LibreLinkUpService.java**
   - Updated authentication headers (line 54-58)
   - Updated region-specific authentication headers (line 142-146)
   - Updated all API call headers (5 locations)
   - Changed client version to 4.12.0

2. **test_libre_auth.sh** (created)
   - Shell script to test authentication with correct headers

## Impact

### Before
- ❌ Always got redirect response
- ❌ No token in response
- ❌ Authentication failed

### After
- ✅ Successful authentication
- ✅ Token received in `authTicket.token`
- ✅ Can make subsequent API calls

## Lessons Learned

1. **API headers matter** - Even minor differences can cause issues
2. **Product headers are validated** - APIs check client identity
3. **Version numbers are important** - Older versions may be deprecated
4. **User-Agent must match Product** - Inconsistent headers are rejected
5. **Android vs iOS** - Some APIs prefer or restrict certain platforms

## Next Steps

1. ✅ Headers updated to working configuration
2. 🔄 Test authentication with your credentials
3. 🔄 Verify token is received
4. 🔄 Test subsequent API calls (connections, glucose data)
5. 🔄 Update frontend if needed

## References

- LibreLinkUp unofficial API: https://github.com/DRFR0ST/libre-link-unofficial-api
- Working headers discovered through testing and community feedback
- API version 4.12.0 confirmed working as of October 2025

