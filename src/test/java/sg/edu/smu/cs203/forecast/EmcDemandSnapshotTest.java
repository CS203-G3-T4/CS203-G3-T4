package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class EmcDemandSnapshotTest {
 static final ObjectMapper M=new ObjectMapper();
 static final Instant NOW=Instant.parse("2026-12-31T15:33:00Z");
 static ObjectNode snapshot(Instant now) { return snapshot(now,0); }
 static ObjectNode snapshot(Instant now,int pastPeriods) {
  var root=M.createObjectNode().put("schemaVersion",1);var item=root.putArray("responses").addObject().put("value",12)
   .put("url",EmcDemandSnapshotImporter.URL).put("receivedAt",now.minusSeconds(1).toString());
  var panel=item.putObject("body").put("success",true).putObject("data").putArray("data").addObject();
  var first=BaselineForecastService.floor(now).atZone(EmcDemandSnapshotImporter.SGT);
  panel.put("lastupdate",first.plusMinutes(1).format(EmcDemandSnapshotImporter.UPDATE));
  panel.put("currentdate",first.format(EmcDemandSnapshotImporter.UPDATE)+"-"+first.plusMinutes(30).format(DateTimeFormatter.ofPattern("HH:mm")));
  var labels=panel.putArray("labels");var values=panel.putArray("datasets").addObject().put("label","Demand").putArray("data");
  for(int i=0;i<72;i++) {var start=first.plusMinutes(30L*(i-pastPeriods));labels.add(start.format(DateTimeFormatter.ofPattern("dd MMM HH:mm",Locale.ENGLISH))+"-"+start.plusMinutes(30).format(DateTimeFormatter.ofPattern("HH:mm")));values.add("6,500");}
  return root;
 }
 @Test void futureDemandHasActualPublicationAndReceiptAcrossNewYear() {
  var rows=EmcDemandSnapshotImporter.parse(snapshot(NOW),NOW);
  assertThat(rows).hasSize(24);assertThat(rows.getFirst().targetPeriod()).isEqualTo(BaselineForecastService.floor(NOW).plusSeconds(1800));
  assertThat(rows.getLast().targetPeriod()).isEqualTo(BaselineForecastService.floor(NOW).plusSeconds(43200));
  assertThat(rows).allSatisfy(row->{assertThat(row.availableAt()).isEqualTo(NOW.minusSeconds(1));assertThat(row.publishedAt()).isBefore(row.availableAt());assertThat(row.demandMw()).isEqualByComparingTo("6500");});
 }
 @Test void morningWindowMayContainPastPeriodsButOnlyFutureDemandIsImported() {
  var rows=EmcDemandSnapshotImporter.parse(snapshot(NOW,40),NOW);
  assertThat(rows).hasSize(24).allSatisfy(row->assertThat(row.targetPeriod()).isAfter(NOW));
 }
 @ParameterizedTest @ValueSource(strings={"futureReceipt","staleReceipt","stalePublication","futurePublication","wrongSource","negative","nonfinite","duplicateLabel","duplicateDemand","badEnd","missing"})
 void invalidSnapshotsDoNotProduceDemand(String kind) {
  var root=snapshot(NOW);var item=(ObjectNode)root.path("responses").get(0);var panel=(ObjectNode)item.path("body").path("data").path("data").get(0);
  var values=(tools.jackson.databind.node.ArrayNode)panel.path("datasets").get(0).path("data");
  switch(kind) {
   case "futureReceipt"->item.put("receivedAt",NOW.plusSeconds(1).toString());
   case "staleReceipt"->item.put("receivedAt",NOW.minusSeconds(601).toString());
   case "stalePublication"->panel.put("lastupdate",NOW.minusSeconds(3600).atZone(EmcDemandSnapshotImporter.SGT).format(EmcDemandSnapshotImporter.UPDATE));
   case "futurePublication"->panel.put("lastupdate",NOW.plusSeconds(60).atZone(EmcDemandSnapshotImporter.SGT).format(EmcDemandSnapshotImporter.UPDATE));
   case "wrongSource"->item.put("url","https://example.com");
   case "negative"->values.set(1,M.getNodeFactory().textNode("-1"));
   case "nonfinite"->values.set(1,M.getNodeFactory().textNode("NaN"));
   case "duplicateLabel"->((tools.jackson.databind.node.ArrayNode)panel.path("labels")).set(1,panel.path("labels").get(0));
   case "duplicateDemand"->((tools.jackson.databind.node.ArrayNode)panel.path("datasets")).add(panel.path("datasets").get(0).deepCopy());
   case "badEnd"->((tools.jackson.databind.node.ArrayNode)panel.path("labels")).set(1,M.getNodeFactory().textNode("01 Jan 00:00-00:31"));
   case "missing"->values.remove(1);
  }
  assertThatThrownBy(()->EmcDemandSnapshotImporter.parse(root,NOW)).isInstanceOf(IllegalArgumentException.class);
 }
}
