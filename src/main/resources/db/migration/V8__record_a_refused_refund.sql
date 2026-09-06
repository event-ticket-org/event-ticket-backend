-- requirements/008 criterion 7, which V7 left half done.
--
-- A cancellation refunds every paid Order one at a time, and some of them are refused before a
-- provider is ever asked - a Ticket already used at the door is the ordinary case. Those
-- refusals were logged and nowhere else, so the per-Order report showed them as REFUND_PENDING
-- for ever: "still going" on a screen somebody is watching to find out which Orders they have
-- to finish by hand.
--
-- Recording the refusal as a failed refund is what makes that report true. Such a row has no
-- provider_ref, because no provider was ever asked - which is the whole reason it is a refusal
-- rather than a failure.
ALTER TABLE refund ALTER COLUMN provider_ref DROP NOT NULL;

-- The unique constraint tolerates this: Postgres treats NULLs as distinct, so any number of
-- refused rows can sit beside one real attempt. refund_one_live_per_order already excludes
-- REFUND_FAILED, which is what lets a refusal be recorded and the Order still be refundable
-- once whatever caused the refusal is dealt with.
