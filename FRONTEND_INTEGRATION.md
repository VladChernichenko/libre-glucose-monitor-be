# Frontend Integration Guide

This guide explains how to integrate your frontend glucose monitoring application with the new Spring Boot backend using feature toggles.

## Feature Toggle System

The backend implements a feature toggle system that allows you to gradually migrate from frontend logic to backend services. This follows the Strangler pattern for safe, incremental migration.

### How It Works

1. **Feature Toggles**: Each service can be individually enabled/disabled
2. **Gradual Migration**: Users can be migrated in percentages (0-100%)
3. **Fallback**: If a feature is disabled, the frontend continues to use its existing logic
4. **Monitoring**: Real-time status of which features are using the backend

## API Endpoints

### Feature Status
```
GET /api/features/status
```
Returns the current status of all feature toggles.

### Check Specific Feature
```
GET /api/features/check/{feature}?userId={userId}
```
Check if a specific feature should use the backend for a given user.

### Insulin Calculator
```
POST /api/insulin/calculate
GET /api/insulin/status
POST /api/insulin/active-insulin
```

## Integration Strategy

### Phase 1: Feature Detection
```typescript
// Check if backend features are available
async function checkBackendFeatures() {
  const response = await fetch('/api/features/status');
  const features = await response.json();
  
  if (features.backendModeEnabled) {
    // Backend is available, check specific features
    if (features.insulinCalculator.enabled) {
      // Insulin calculator can use backend
      console.log('Using backend insulin calculator');
    }
  }
}
```

### Phase 2: Conditional Logic
```typescript
// Example: Insulin calculation with fallback
async function calculateInsulin(carbs: number, currentGlucose?: number) {
  try {
    // Check if backend is available
    const statusResponse = await fetch(`/api/insulin/status?userId=${getCurrentUserId()}`);
    const status = await statusResponse.json();
    
    if (status.backendMode && status.shouldMigrate) {
      // Use backend
      const response = await fetch('/api/insulin/calculate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          carbs,
          currentGlucose,
          targetGlucose: 7.0,
          userId: getCurrentUserId()
        })
      });
      
      const result = await response.json();
      return result.data.recommendedInsulin;
    } else {
      // Fallback to frontend logic
      return useFrontendInsulinCalculator(carbs, currentGlucose);
    }
  } catch (error) {
    // Fallback to frontend logic on error
    console.warn('Backend unavailable, using frontend logic:', error);
    return useFrontendInsulinCalculator(carbs, currentGlucose);
  }
}
```

### Phase 3: Gradual Migration
```typescript
// Monitor migration progress
async function getMigrationStatus() {
  const response = await fetch('/api/features/status');
  const features = await response.json();
  
  const insulinProgress = features.insulinCalculator.migrationPercent;
  const carbsProgress = features.carbsOnBoard.migrationPercent;
  
  console.log(`Migration Progress: Insulin ${insulinProgress}%, Carbs ${carbsProgress}%`);
}
```

## Configuration

### Enable Backend Mode
Set in `application.properties`:
```properties
app.features.backend-mode-enabled=true
```

### Enable Specific Features
```properties
app.features.insulin-calculator-enabled=true
app.features.carbs-on-board-enabled=true
```

### Set Migration Percentages
```properties
# Migrate 25% of users to backend insulin calculator
app.features.insulin-calculator-migration-percent=25

# Migrate 50% of users to backend carbs on board
app.features.carbs-on-board-migration-percent=50
```

## Error Handling

Always implement fallback logic:
```typescript
try {
  const backendResult = await callBackendService();
  return backendResult;
} catch (error) {
  console.warn('Backend service failed, using frontend logic:', error);
  return useFrontendLogic();
}
```

## Monitoring

Use the feature status endpoints to monitor:
- Which features are enabled
- Migration progress
- Backend availability
- User migration status

## Benefits

1. **Zero Downtime**: Frontend continues working while backend is deployed
2. **Gradual Migration**: Users can be migrated in small batches
3. **Easy Rollback**: Disable features instantly if issues arise
4. **Performance Comparison**: Compare frontend vs backend performance
5. **User Experience**: Users don't notice the migration happening

## Next Steps

1. Implement feature detection in your frontend
2. Add conditional logic for backend vs frontend services
3. Test with small user groups (low migration percentages)
4. Monitor performance and gradually increase migration percentages
5. Eventually migrate 100% of users and remove frontend logic
