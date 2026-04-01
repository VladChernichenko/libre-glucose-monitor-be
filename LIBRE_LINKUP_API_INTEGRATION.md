# Libre Link Up API Integration

## Overview

This document describes the Libre Link Up API integration in the glucose monitor backend, based on the [unofficial Libre Link Up API library](https://github.com/DRFR0ST/libre-link-unofficial-api).

## API Endpoints

### Authentication
- **POST** `/api/libre/auth/login` - Authenticate with LibreLinkUp credentials

### Patient Management
- **GET** `/api/libre/connections` - Get all patient connections
- **GET** `/api/libre/profile` - Get user profile information

### Glucose Data
- **GET** `/api/libre/connections/{patientId}/graph` - Get glucose data for a specific patient
- **GET** `/api/libre/connections/{patientId}/current` - Get current glucose reading
- **GET** `/api/libre/connections/{patientId}/history` - Get historical glucose data
- **GET** `/api/libre/connections/{patientId}/raw` - Get raw glucose reading (unprocessed)

## Data Flow

### 1. Authentication
```typescript
// Frontend
const authResponse = await libreApiService.authenticate(email, password);
// Backend: POST /api/libre/auth/login
// LibreLinkUp API: POST /llu/auth/login
```

### 2. Get Connections
```typescript
// Frontend
const connections = await libreApiService.getConnections();
// Backend: GET /api/libre/connections
// LibreLinkUp API: GET /llu/connections
```

### 3. Get Glucose Data
```typescript
// Frontend
const glucoseData = await libreApiService.getGlucoseData(patientId, days);
// Backend: GET /api/libre/connections/{patientId}/graph
// LibreLinkUp API: GET /llu/connections/{patientId}/graph
```

## Data Conversion

### Units
- **Input**: mg/dL from LibreLinkUp API
- **Output**: mmol/L for internal processing
- **Conversion**: `mmol/L = mg/dL / 18.0`

### Trend Arrows
- **1**: ↗ (Rising)
- **2**: ↘ (Falling)  
- **3**: → (Stable)
- **4**: ? (No data)

### Glucose Status
- **low**: < 3.9 mmol/L (< 70 mg/dL)
- **normal**: 3.9-10.0 mmol/L (70-180 mg/dL)
- **high**: 10.0-13.9 mmol/L (180-250 mg/dL)
- **critical**: > 13.9 mmol/L (> 250 mg/dL)

## Error Handling

### Circuit Breaker Pattern
The service implements circuit breaker pattern for resilience:
- **libre-auth**: Authentication requests
- **libre-connections**: Connection fetching
- **libre-glucose-data**: Glucose data retrieval
- **libre-glucose-history**: Historical data
- **libre-raw-reading**: Raw data retrieval
- **libre-profile**: Profile information

### Fallback Responses
When circuit breakers are open, the service returns:
- Empty arrays for data endpoints
- Empty objects for profile endpoints
- Error messages for authentication

## Configuration

### Environment Variables
```yaml
libre:
  api:
    base-url: https://api.libreview.io
```

### Headers
All requests include:
- `Authorization: Bearer {token}`
- `Accept: application/json`
- `Product: llu.ios`
- `Version: 4.9.0`
- `User-Agent: LibreLinkUp/4.9.0 (iOS)`

## Usage Examples

### Frontend Integration
```typescript
import { libreApiService } from './services/libreApi';

// Authenticate
await libreApiService.authenticate('user@example.com', 'password');

// Get connections
const connections = await libreApiService.getConnections();

// Get glucose data
const glucoseData = await libreApiService.getGlucoseData(patientId, 7);

// Get historical data
const history = await libreApiService.getGlucoseHistory(patientId, 30);

// Get raw data
const rawData = await libreApiService.getRawGlucoseReading(patientId);
```

### Backend Service
```java
@Autowired
private LibreLinkUpService libreLinkUpService;

// Authenticate
LibreAuthResponse auth = libreLinkUpService.authenticate(authRequest);

// Get connections
List<LibreConnection> connections = libreLinkUpService.getConnections();

// Get glucose data
LibreGlucoseData data = libreLinkUpService.getGlucoseData(patientId, days);
```

## Security Considerations

1. **Token Management**: Authentication tokens are stored in memory and cleared on logout
2. **Circuit Breakers**: Prevent cascading failures and API abuse
3. **Error Handling**: Sensitive information is not exposed in error messages
4. **Rate Limiting**: Circuit breakers provide implicit rate limiting

## Monitoring

### Logging
- All API calls are logged with patient ID and user context
- Error conditions are logged with full stack traces
- Circuit breaker state changes are logged

### Metrics
- Request success/failure rates
- Circuit breaker open/close events
- Response times
- Data volume processed

## Troubleshooting

### Common Issues

1. **Authentication Failures**
   - Check credentials
   - Verify LibreLinkUp account status
   - Check API version compatibility

2. **No Data Returned**
   - Verify patient ID
   - Check sensor connection status
   - Verify date ranges

3. **Circuit Breaker Open**
   - Check LibreLinkUp API status
   - Verify network connectivity
   - Review error logs

### Debug Mode
Enable debug logging:
```yaml
logging:
  level:
    che.glucosemonitorbe.service.LibreLinkUpService: DEBUG
```

## Future Enhancements

1. **Caching**: Implement Redis caching for frequently accessed data
2. **Streaming**: Add WebSocket support for real-time data streaming
3. **Batch Processing**: Support for bulk data operations
4. **Analytics**: Add data analytics and trend analysis
5. **Notifications**: Real-time alerts for glucose levels

## References

- [Libre Link Up API Library](https://github.com/DRFR0ST/libre-link-unofficial-api)
- [LibreLinkUp Official Website](https://www.libreview.com)
- [Abbott Libre Documentation](https://www.freestylelibre.com)
