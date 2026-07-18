package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);

    /** Users whose email does not match {@code emailPattern} (SQL LIKE) - e.g. to exclude seed fixtures. */
    List<User> findByEmailNotLike(String emailPattern);
}

