package che.glucosemonitorbe.mapper;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.UserDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {
    User map(UserDto userDto);
    UserDto map(User user);
}
