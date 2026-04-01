package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.InsulinCatalogDTO;
import che.glucosemonitorbe.entity.InsulinCatalog;
import che.glucosemonitorbe.repository.InsulinCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InsulinCatalogService {

    private final InsulinCatalogRepository insulinCatalogRepository;

    public List<InsulinCatalogDTO> findAll() {
        return insulinCatalogRepository.findAll().stream()
                .map(InsulinCatalogService::toDto)
                .collect(Collectors.toList());
    }

    public List<InsulinCatalogDTO> findByCategory(InsulinCatalog.Category category) {
        return insulinCatalogRepository.findByCategoryOrderByDisplayNameAsc(category).stream()
                .map(InsulinCatalogService::toDto)
                .collect(Collectors.toList());
    }

    public InsulinCatalog getRequiredByCode(String code) {
        return insulinCatalogRepository.findById(code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown insulin code: " + code));
    }

    static InsulinCatalogDTO toDto(InsulinCatalog e) {
        return InsulinCatalogDTO.builder()
                .code(e.getCode())
                .category(e.getCategory().name())
                .displayName(e.getDisplayName())
                .peakMinutes(e.getPeakMinutes())
                .diaHours(e.getDiaHours())
                .halfLifeMinutes(e.getHalfLifeMinutes())
                .onsetMinutes(e.getOnsetMinutes())
                .description(e.getDescription())
                .build();
    }
}
