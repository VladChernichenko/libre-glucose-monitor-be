package che.glucosemonitorbe.nightscout;

import che.glucosemonitorbe.dto.NightscoutEntryDto;
import che.glucosemonitorbe.dto.NightscoutDeviceStatusDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "nightScout", url = "${nightscout.base-url:https://vladchernichenko.eu.nightscoutpro.com}")
public interface NightScoutClient {
    
    @GetMapping("/api/v2/entries.json")
    List<NightscoutEntryDto> getGlucoseEntries(
            @RequestParam(value = "count", defaultValue = "100") int count,
            @RequestHeader(value = "api-secret", required = false) String apiSecret,
            @RequestHeader(value = "Authorization", required = false) String authorization
    );
    
    @GetMapping("/api/v2/entries.json")
    List<NightscoutEntryDto> getGlucoseEntriesByDate(
            @RequestParam("find[date][$gte]") String startDate,
            @RequestParam("find[date][$lte]") String endDate,
            @RequestHeader(value = "api-secret", required = false) String apiSecret,
            @RequestHeader(value = "Authorization", required = false) String authorization
    );
    
    @GetMapping("/api/v2/devicestatus.json")
    List<NightscoutDeviceStatusDto> getDeviceStatus(
            @RequestParam(value = "count", defaultValue = "1") int count,
            @RequestHeader(value = "api-secret", required = false) String apiSecret,
            @RequestHeader(value = "Authorization", required = false) String authorization
    );
}
