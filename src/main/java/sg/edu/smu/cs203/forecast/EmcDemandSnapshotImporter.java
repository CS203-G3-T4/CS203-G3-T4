package sg.edu.smu.cs203.forecast;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/** Read only the explicitly configured, receipt-stamped public snapshot; never make a network request. */
@Component
public class EmcDemandSnapshotImporter {
    static final String SOURCE="EMC_PUBLIC_72_PERIOD_DEMAND";
    static final String URL="https://www.nems.emcsg.com/api/DataSync/Get?value=12&fromDate=&toDate=";
    static final ZoneId SGT=ZoneId.of("Asia/Singapore");
    static final DateTimeFormatter UPDATE=DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm",Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    private final ObjectMapper mapper;
    private final ForecastOperationsRepository operations;
    private final Clock clock;
    private final String path;
    public EmcDemandSnapshotImporter(ObjectMapper mapper,ForecastOperationsRepository operations,Clock clock,
            @Value("${forecast.emc-demand.snapshot:}") String path) {
        this.mapper=mapper;this.operations=operations;this.clock=clock;this.path=path;
    }
    public int importLatest() throws IOException {
        if(path.isBlank()) return 0;
        Path file=Path.of(path);
        if(Files.size(file)>800_000) throw new IllegalArgumentException("EMC snapshot exceeds limit");
        byte[] bytes=Files.readAllBytes(file);
        if(bytes.length>800_000) throw new IllegalArgumentException("EMC snapshot exceeds limit");
        Instant now=clock.instant();
        var rows=parse(mapper.readTree(bytes),now);
        return operations.saveNewDemand(rows,now);
    }
    static List<ForecastOperationsRepository.Demand> parse(JsonNode snapshot,Instant now) {
        if(snapshot.path("schemaVersion").asInt()!=1 || !snapshot.path("responses").isArray()
                || snapshot.path("responses").size()>3) throw new IllegalArgumentException("Invalid EMC snapshot");
        JsonNode item=null;
        for(var candidate:snapshot.path("responses")) if(candidate.path("value").asInt()==12) {
            if(item!=null) throw new IllegalArgumentException("Duplicate EMC demand panel");item=candidate;
        }
        if(item==null || !URL.equals(item.path("url").asText())) throw new IllegalArgumentException("Invalid EMC demand source");
        Instant receipt=Instant.parse(item.path("receivedAt").asText());
        if(receipt.isAfter(now) || receipt.isBefore(now.minusSeconds(600))) throw new IllegalArgumentException("Stale or future EMC receipt");
        var body=item.path("body");var panels=body.path("data").path("data");
        if(!body.path("success").asBoolean() || panels.size()!=1) throw new IllegalArgumentException("Unsuccessful EMC demand response");
        var panel=panels.get(0);
        var local=LocalDateTime.parse(panel.path("lastupdate").asText(),UPDATE);
        Instant published=local.atZone(SGT).toInstant();
        if(published.isAfter(receipt) || published.isBefore(now.minusSeconds(2400))) throw new IllegalArgumentException("Stale or future EMC publication");
        var current=panel.path("currentdate").asText();
        if(!current.equals(local.withMinute(local.getMinute()/30*30).format(UPDATE)+"-"+local.withMinute(local.getMinute()/30*30).plusMinutes(30).format(DateTimeFormatter.ofPattern("HH:mm"))))
            throw new IllegalArgumentException("EMC current-period mapping disagrees");
        JsonNode demand=null;
        for(var series:panel.path("datasets")) if("Demand".equals(series.path("label").asText())) {
            if(demand!=null) throw new IllegalArgumentException("Ambiguous EMC demand");demand=series.path("data");
        }
        var labels=panel.path("labels");
        if(demand==null || !demand.isArray() || demand.size()!=72 || labels.size()!=72) throw new IllegalArgumentException("Misaligned EMC demand");
        List<ForecastOperationsRepository.Demand> rows=new ArrayList<>();Instant previous=null;
        for(int i=0;i<72;i++) {
            String label=labels.get(i).asText();
            if(!label.matches("\\d{2} [A-Z][a-z]{2} \\d{2}:\\d{2}-\\d{2}:\\d{2}")) throw new IllegalArgumentException("Invalid EMC interval label");
            // The forward window starts at current period and may cross New Year.
            LocalDateTime start=null;
            for(int year:List.of(local.getYear()-1,local.getYear(),local.getYear()+1)) {
                try {
                    var t=LocalDateTime.parse(label.substring(0,6)+" "+year+label.substring(6,12),UPDATE);
                    if(start==null || Duration.between(local,t).abs().compareTo(Duration.between(local,start).abs())<0) start=t;
                } catch(DateTimeException ignored) { }
            }
            if(start==null || Duration.between(local,start).abs().toDays()>3 || start.getMinute()%30!=0
                    || !start.plusMinutes(30).format(DateTimeFormatter.ofPattern("HH:mm")).equals(label.substring(13)))
                throw new IllegalArgumentException("Invalid EMC interval mapping");
            Instant target=start.atZone(SGT).toInstant();
            if(previous==null && (target.isAfter(BaselineForecastService.floor(published)) || target.isBefore(BaselineForecastService.floor(published).minusSeconds(86400))) || previous!=null && !target.equals(previous.plusSeconds(1800)))
                throw new IllegalArgumentException("Nonconsecutive EMC demand grid");
            previous=target;
            if(!target.isAfter(BaselineForecastService.floor(now)) || target.isAfter(BaselineForecastService.floor(now).plusSeconds(43200))) continue;
            BigDecimal value;
            try {value=new BigDecimal(demand.get(i).asText().replace(",",""));}
            catch(NumberFormatException e) {throw new IllegalArgumentException("Missing/nonfinite EMC demand",e);}
            if(value.signum()<0 || value.compareTo(BigDecimal.valueOf(100_000))>0) throw new IllegalArgumentException("Invalid EMC demand magnitude");
            rows.add(new ForecastOperationsRepository.Demand(target,published,receipt,value,SOURCE));
        }
        if(rows.size()!=24) throw new IllegalArgumentException("Incomplete 12-hour EMC demand forecast");
        return rows;
    }
}
