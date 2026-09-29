package sg.edu.smu.cs203.market.price;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MarketHistoryRepository {
    private final JdbcTemplate jdbc;

    public MarketHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public boolean save(MarketRevision r, String origin, String externalId, String document) {
        Object[] values = {r.source(), ts(r.periodStart()), ts(r.sourceUpdatedAt()), ts(r.availableAt()),
                r.usep(), r.demand(), r.vcp(), r.priceStatus(), r.timeMapping()};
        List<Long> changed = jdbc.query("""
                INSERT INTO market_price_revision(source,period_start,published_at,available_at,usep,demand,vcp,price_status,time_mapping)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (source,period_start,published_at,usep,demand,vcp,price_status,time_mapping)
                DO UPDATE SET available_at=LEAST(market_price_revision.available_at,EXCLUDED.available_at)
                WHERE EXCLUDED.available_at < market_price_revision.available_at
                RETURNING id
                """, (rs,n) -> rs.getLong(1), values);
        long id = changed.isEmpty() ? jdbc.queryForObject("""
                SELECT id FROM market_price_revision WHERE source=? AND period_start=?
                AND published_at IS NOT DISTINCT FROM ? AND usep=? AND demand IS NOT DISTINCT FROM ?
                AND vcp IS NOT DISTINCT FROM ? AND price_status=? AND time_mapping=?
                """, Long.class, r.source(),ts(r.periodStart()),ts(r.sourceUpdatedAt()),r.usep(),r.demand(),r.vcp(),r.priceStatus(),r.timeMapping()) : changed.getFirst();
        jdbc.update("""
                INSERT INTO market_revision_origin(origin,external_id,revision_id,document) VALUES (?,?,?,?::jsonb)
                ON CONFLICT(origin,external_id) DO NOTHING
                """, origin,externalId,id,document);
        Long recorded = jdbc.queryForObject("SELECT revision_id FROM market_revision_origin WHERE origin=? AND external_id=?",
                Long.class,origin,externalId);
        if (recorded == null || recorded != id) throw new IllegalArgumentException("External ID changed its canonical meaning");
        return !changed.isEmpty();
    }

    public List<MarketRevision> asOf(Instant asOf) {
        List<MarketRevision> rows = jdbc.query("""
                SELECT * FROM market_price_revision
                WHERE period_start>=? AND period_start<=? AND published_at<=? AND available_at<=?
                ORDER BY period_start,published_at DESC,available_at DESC,id DESC
                """, (rs,n) -> new MarketRevision(rs.getLong("id"),rs.getString("source"),
                rs.getTimestamp("period_start").toInstant(),rs.getTimestamp("published_at").toInstant(),
                rs.getTimestamp("available_at").toInstant(),rs.getBigDecimal("usep"),rs.getBigDecimal("demand"),
                rs.getBigDecimal("vcp"),rs.getString("price_status"),rs.getString("time_mapping")),
                ts(asOf.minus(Duration.ofDays(29))),ts(asOf),ts(asOf),ts(asOf));
        var selected = new java.util.LinkedHashMap<Instant,MarketRevision>();
        for (MarketRevision row : rows) {
            MarketRevision prior = selected.putIfAbsent(row.periodStart(),row);
            if (prior != null && prior.sourceUpdatedAt().equals(row.sourceUpdatedAt())
                    && prior.availableAt().equals(row.availableAt()) && prior.usep().compareTo(row.usep()) != 0) {
                throw new IllegalStateException("Conflicting market revisions at " + row.periodStart());
            }
        }
        return List.copyOf(selected.values());
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
