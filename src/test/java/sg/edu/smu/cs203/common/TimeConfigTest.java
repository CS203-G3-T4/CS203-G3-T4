package sg.edu.smu.cs203.common;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigTest {
    @Test
    void sharedClockUsesSingaporeZone() {
        assertThat(new TimeConfig().clock().getZone()).isEqualTo(ZoneId.of("Asia/Singapore"));
    }
}
