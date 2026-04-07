package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.InsulinCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsulinCatalogRepository extends JpaRepository<InsulinCatalog, UUID> {

    List<InsulinCatalog> findByCategoryOrderByDisplayNameAsc(InsulinCatalog.Category category);

    Optional<InsulinCatalog> findByCode(String code);
}
