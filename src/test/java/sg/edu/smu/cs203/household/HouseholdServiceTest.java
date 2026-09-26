package sg.edu.smu.cs203.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import sg.edu.smu.cs203.household.load.LoadArchetype;
import sg.edu.smu.cs203.household.load.LoadArchetypeRepository;

class HouseholdServiceTest {

    private final Instant now = Instant.parse("2026-09-26T06:00:00Z");
    private final HouseholdRepository households = mock(HouseholdRepository.class);
    private final LoadArchetypeRepository archetypes = mock(LoadArchetypeRepository.class);
    private final HouseholdService service =
            new HouseholdService(households, archetypes, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void priceLinkedHouseholdIsExposedToWholesalePrices() {
        when(households.findById(1L)).thenReturn(Optional.of(household(PlanType.PRICE_LINKED, null)));

        HouseholdResponse response = service.get(1L);

        assertThat(response.exposedToWholesalePrice()).isTrue();
        assertThat(response.exposureLabel()).isEqualTo("Price-linked (USEP)");
    }

    @Test
    void fixedRateHouseholdIsFlaggedNotExposed() {
        when(households.findById(1L)).thenReturn(
                Optional.of(household(PlanType.FIXED, new BigDecimal("29.5"))));

        HouseholdResponse response = service.get(1L);

        assertThat(response.exposedToWholesalePrice()).isFalse();
        assertThat(response.exposureLabel()).isEqualTo("Not exposed (fixed rate)");
    }

    @Test
    void unknownHouseholdIsNotFound() {
        when(households.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(HouseholdNotFoundException.class);
    }

    @Test
    void fixedPlanWithoutARateIsRejected() {
        when(households.findById(1L)).thenReturn(Optional.of(household(PlanType.PRICE_LINKED, null)));
        HouseholdRequest request = new HouseholdRequest("The Tans", DwellingType.HDB_4_ROOM, 4,
                PlanType.FIXED, null);

        assertThatThrownBy(() -> service.update(1L, request))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                        .containsKey("fixedRateCentsPerKwh"));
        verify(households, never()).update(any());
    }

    @Test
    void switchingToPriceLinkedDropsTheFixedRateAndUsesTheClock() {
        when(households.findById(1L)).thenReturn(
                Optional.of(household(PlanType.FIXED, new BigDecimal("29.5"))));
        when(households.update(any())).thenReturn(true);
        HouseholdRequest request = new HouseholdRequest(" The Tans ", DwellingType.HDB_4_ROOM, 4,
                PlanType.PRICE_LINKED, new BigDecimal("29.5"));

        service.update(1L, request);

        ArgumentCaptor<Household> saved = ArgumentCaptor.forClass(Household.class);
        verify(households).update(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("The Tans");
        assertThat(saved.getValue().fixedRateCentsPerKwh()).isNull();
        assertThat(saved.getValue().updatedAt()).isEqualTo(now);
    }

    @Test
    void changingHomeTypeSwitchesToThatHomeTypesArchetype() {
        when(households.findById(1L)).thenReturn(Optional.of(household(PlanType.PRICE_LINKED, null)));
        when(households.update(any())).thenReturn(true);
        when(archetypes.findByDwellingType(DwellingType.LANDED)).thenReturn(Optional.of(
                new LoadArchetype(6L, "LANDED", DwellingType.LANDED, "Landed property",
                        new BigDecimal("1208.2"), new BigDecimal("1.10"), "EMA")));
        HouseholdRequest request = new HouseholdRequest("The Tans", DwellingType.LANDED, 4,
                PlanType.PRICE_LINKED, null);

        service.update(1L, request);

        ArgumentCaptor<Household> saved = ArgumentCaptor.forClass(Household.class);
        verify(households).update(saved.capture());
        assertThat(saved.getValue().archetypeId()).isEqualTo(6L);
    }

    private Household household(PlanType plan, BigDecimal rate) {
        Instant created = Instant.parse("2026-09-20T00:00:00Z");
        return new Household(1L, "The Tan Household", DwellingType.HDB_4_ROOM, 4, plan, rate,
                3L, false, created, created);
    }
}
