-- F4 (CSDT4-39): how often the suggested time was really cheaper than the usual time, from
-- actual prices on the same times of day over the last 28 days. Both NULL when fewer than 14
-- days could be checked ("not enough price history yet"); never a made-up number.
ALTER TABLE recommendation ADD COLUMN confidence_cheaper_days INTEGER;
ALTER TABLE recommendation ADD COLUMN confidence_days_checked INTEGER;
ALTER TABLE recommendation ADD CONSTRAINT ck_recommendation_confidence
    CHECK ((confidence_cheaper_days IS NULL) = (confidence_days_checked IS NULL)
           AND (confidence_days_checked IS NULL
                OR (confidence_days_checked BETWEEN 1 AND 28
                    AND confidence_cheaper_days BETWEEN 0 AND confidence_days_checked)));

-- Explanations now carry the prices, times and window limit behind each suggestion.
ALTER TABLE recommendation ALTER COLUMN reason TYPE VARCHAR(600);
