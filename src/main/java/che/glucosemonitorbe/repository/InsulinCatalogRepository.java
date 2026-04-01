package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.InsulinCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InsulinCatalogRepository extends JpaRepository<InsulinCatalog, String> {

    List<InsulinCatalog> findByCategoryOrderByDisplayNameAsc(InsulinCatalog.Category category);
}
