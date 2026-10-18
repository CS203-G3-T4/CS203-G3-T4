-- F4 (CSDT4-40): "Remind me later" works on any open suggestion, not only spike warnings
-- (CSDT4-38), and hides it until a time the resident picks (snoozed_until).
ALTER TABLE recommendation DROP CONSTRAINT ck_recommendation_snooze;
