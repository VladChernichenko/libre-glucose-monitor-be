package che.glucosemonitorbe.service;

import che.glucosemonitorbe.exception.UsernameAlreadyExistsException;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.mapper.UserMapper;
import che.glucosemonitorbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Base64;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;


    public User findByUsername(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    public UserDto saveUser(String email, String password, String fullName) {
        if (!userRepository.existsByEmail(email)) {
            UserDto userDto = new UserDto();
            userDto.setEmail(email);
            userDto.setFullName(fullName);
            userDto.setPasswordHash(Base64.getEncoder().encodeToString(password.getBytes()));
            return userMapper.map(userRepository.save(userMapper.map(userDto)));
        }
        throw new UsernameAlreadyExistsException("Username already exists");
    }
}
