package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.OFFProductDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OFFProductRepository extends MongoRepository<OFFProductDocument, String> {

    Optional<OFFProductDocument> findByCode(String code);

    @Query("{ 'product_name': { $regex: ?0, $options: 'i' } }")
    List<OFFProductDocument> findByProductNameContaining(String query, Pageable pageable);
}
