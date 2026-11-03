package sg.edu.smu.cs203.recommendation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Offline adapter only. Planning uses predictions; actuals are evaluated afterward. */
public final class DemandSavingsReplay {
    public static void main(String[] args) throws Exception {
        try (var reader=new BufferedReader(new InputStreamReader(System.in))) {
            for(String line;(line=reader.readLine())!=null;) {
                String[] v=line.split("\t",-1);
                if(v.length!=12)throw new IllegalArgumentException("Invalid replay row");
                Instant origin=Instant.parse(v[1]);
                var run=new CheapestStartCalculator.ShiftableRun(new BigDecimal(v[2]),Integer.parseInt(v[3]),
                    LocalTime.parse(v[4]),LocalTime.parse(v[5]),LocalTime.parse(v[6]));
                String[] values=v[8].split(","),flags=v[9].split(",");
                if(values.length!=24 || flags.length!=24)throw new IllegalArgumentException("Require 24 predictions");
                var predictions=new ArrayList<PriceForecast.HalfHourPrice>();
                long boundary=Math.floorDiv(origin.getEpochSecond(),1800)*1800;
                for(int h=0;h<24;h++)predictions.add(new PriceForecast.HalfHourPrice(
                    Instant.ofEpochSecond(boundary+(h+1)*1800),new BigDecimal(values[h]),flags[h].equals("1")));
                var result=CheapestStartCalculator.cheapestStart(run,predictions,origin,new BigDecimal(v[7]));
                if(result.outcome()==CheapestStartCalculator.Outcome.NOT_ENOUGH_FORECAST) {
                    System.out.println(v[0]+"\tNOT_ENOUGH_FORECAST");continue;
                }
                boolean moved=result.outcome()==CheapestStartCalculator.Outcome.SHIFT || result.outcome()==CheapestStartCalculator.Outcome.AVOID;
                Instant chosen=moved ? result.bestStart() : result.usualStart();
                // Actual prices never enter cheapestStart or the ranking saving.
                String[] actual=v[10].split(",");
                if(actual.length!=24)throw new IllegalArgumentException("Require 24 actuals");
                var truth=new HashMap<Instant,BigDecimal>();
                for(int h=0;h<24;h++)truth.put(predictions.get(h).periodStart(),new BigDecimal(actual[h]));
                BigDecimal usual=CheapestStartCalculator.costOf(result.usualStart(),run,truth);
                BigDecimal cost=CheapestStartCalculator.costOf(chosen,run,truth);
                if(usual==null || cost==null)throw new IllegalArgumentException("Incomplete actual run coverage");
                System.out.println(String.join("\t",v[0],result.outcome().name(),
                    Long.toString(result.usualStart().getEpochSecond()),Long.toString(chosen.getEpochSecond()),
                    moved ? result.saving().toPlainString() : "0",usual.toPlainString(),cost.toPlainString(),v[11]));
            }
        }
    }
}
