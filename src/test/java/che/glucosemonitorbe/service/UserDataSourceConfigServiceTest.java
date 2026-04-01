package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.dto.DataSourceConfigRequestDto;
import che.glucosemonitorbe.dto.UserDataSourceConfigDto;
import che.glucosemonitorbe.repository.UserDataSourceConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class UserDataSourceConfigServiceTest {

    @Mock
    private UserDataSourceConfigRepository repository;

    @Mock
    private UserService userService;

    @InjectMocks
    private UserDataSourceConfigService service;

    @Test
    void saveConfigCreatesNightscoutConfig() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        when(userService.getUserById(userId)).thenReturn(user);
        doNothing().when(repository).deactivateConfigsByUserIdAndDataSource(userId, UserDataSourceConfig.DataSourceType.NIGHTSCOUT);

        DataSourceConfigRequestDto request = DataSourceConfigRequestDto.builder()
                .dataSource(UserDataSourceConfig.DataSourceType.NIGHTSCOUT)
                .nightscoutUrl("https://ns.example.com")
                .nightscoutApiSecret("sec")
                .nightscoutApiToken("tok")
                .isActive(true)
                .build();

        UserDataSourceConfig saved = new UserDataSourceConfig(user, "https://ns.example.com", "sec", "tok");
        saved.setId(UUID.randomUUID());
        when(repository.save(any(UserDataSourceConfig.class))).thenReturn(saved);

        UserDataSourceConfigDto result = service.saveConfig(userId, request);

        assertNotNull(result);
        assertEquals(UserDataSourceConfig.DataSourceType.NIGHTSCOUT, result.getDataSource());
        assertEquals("https://ns.example.com", result.getNightscoutUrl());
    }

    @Test
    void getAllConfigsReturnsMappedDtos() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        UserDataSourceConfig config = new UserDataSourceConfig(user, "https://ns.example.com", "sec", "tok");
        config.setId(UUID.randomUUID());
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(config));

        List<UserDataSourceConfigDto> result = service.getAllConfigs(userId);

        assertEquals(1, result.size());
        assertEquals(config.getId(), result.get(0).getId());
        assertEquals(userId, result.get(0).getUserId());
    }

    @Test
    void activateConfigFailsForDifferentUser() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();

        UserDataSourceConfig config = new UserDataSourceConfig();
        config.setId(configId);
        config.setDataSource(UserDataSourceConfig.DataSourceType.NIGHTSCOUT);
        config.setUser(user(otherUserId));
        when(repository.findById(configId)).thenReturn(Optional.of(config));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.activateConfig(userId, configId));
        assertTrue(ex.getMessage().contains("does not belong"));
    }

    @Test
    void deactivateAndDeleteConfigCallsRepository() {
        UUID userId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        UserDataSourceConfig config = new UserDataSourceConfig(user(userId), "https://ns.example.com", "sec", "tok");
        config.setId(configId);
        when(repository.findById(configId)).thenReturn(Optional.of(config));

        service.deactivateConfig(userId, configId);
        service.deleteConfig(userId, configId);

        verify(repository).save(config);
        verify(repository).delete(config);
    }

    @Test
    void testConfigNightscoutReturnsFalseWhenUrlBlank() {
        UUID userId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        UserDataSourceConfig config = new UserDataSourceConfig();
        config.setId(configId);
        config.setUser(user(userId));
        config.setDataSource(UserDataSourceConfig.DataSourceType.NIGHTSCOUT);
        config.setNightscoutUrl("   ");
        when(repository.findById(configId)).thenReturn(Optional.of(config));

        boolean result = service.testConfig(userId, configId);
        assertFalse(result);
    }

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u");
        user.setEmail("u@example.com");
        user.setPassword("p");
        user.setRole(User.Role.USER);
        return user;
    }
}
