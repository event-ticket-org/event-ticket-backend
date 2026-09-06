#!/usr/bin/env bash
#
# Acts as the payment provider again, this time for money going the other way.
#
# requirements/008 criterion 2: a refund settles on a provider confirmation and is never an
# instant boolean, exactly as a payment is (ADR-0002). So the same thing is true of it that is
# true of a payment on a laptop - the organizer can start a refund in the browser and then has
# nothing to wait for, for ever - and this is the other half of confirm-payment.sh.
#
# What it sends is a real webhook: the same path, the same secret, a real HMAC over the raw
# bytes, and a status the provider chooses. Which flow a delivery is about is read from the
# payload after the signature is checked, never from the URL, because that is what real
# providers do - one endpoint, one secret, a typed event.
#
#   scripts/confirm-refund.sh <order-id>                      the money went back
#   scripts/confirm-refund.sh <order-id> --fail               the provider refused it
#   scripts/confirm-refund.sh <order-id> --fail "no account"  and said why
#   scripts/confirm-refund.sh --event <event-id>              settle a whole cancellation
#
set -euo pipefail

BASE=${BASE:-http://localhost:8080/api/v1}
SECRET=${FAKE_PAYMENT_SECRET:-fake-provider-shared-secret}
CONTAINER=${PG_CONTAINER:-event-ticket-backend-postgres-1}

query() {
  docker exec "$CONTAINER" psql -U eventticket -d eventticket -tAc "$1" | tr -d '\r'
}

usage() {
  echo "usage: $0 <order-id> [--fail [reason]]" >&2
  echo "       $0 --event <event-id>" >&2
  echo >&2
  echo "Refunds waiting on the provider:" >&2
  echo "  docker exec $CONTAINER psql -U eventticket -d eventticket -c \\" >&2
  echo "    \"select order_id, status, amount from refund order by created_at desc limit 5\"" >&2
  exit 2
}

# Signed over the raw bytes. A body parsed and re-serialised has a different signature, and
# that mismatch only ever shows up against a real provider.
send() {
  local provider_ref="$1" status="$2" failure="$3"
  local delivery body signature code
  delivery=$(uuidgen | tr '[:upper:]' '[:lower:]')

  if [ -n "$failure" ]; then
    body=$(printf '{"eventId":"%s","providerRef":"%s","status":"%s","failureReason":"%s"}' \
      "$delivery" "$provider_ref" "$status" "$failure")
  else
    body=$(printf '{"eventId":"%s","providerRef":"%s","status":"%s"}' \
      "$delivery" "$provider_ref" "$status")
  fi

  signature=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.*= //')

  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/webhooks/payments/FAKE" \
    -H 'Content-Type: application/json' \
    -H "x-signature: $signature" \
    -d "$body")
  printf '  %s -> %s  HTTP %s\n' "${provider_ref:7:8}" "$status" "$code"
}

# A cancellation is many refunds and settling them one at a time by hand is how a person
# stops testing the interesting case. Refused refunds are skipped: they have no provider_ref
# because no provider was ever asked, which is what makes them refusals rather than failures.
settle_event() {
  local event="$1" refs count=0
  refs=$(query "select r.provider_ref from refund r
                  join ticket_order o on o.id = r.order_id
                 where o.event_id = '$event'
                   and r.status = 'REFUND_PENDING'
                   and r.provider_ref is not null
                 order by r.created_at")

  if [ -z "$refs" ]; then
    echo "No refunds are waiting on the provider for event ${event:0:8}." >&2
    exit 1
  fi

  printf '\033[1msettling every pending refund on event %s\033[0m\n' "${event:0:8}"
  while IFS= read -r ref; do
    [ -z "$ref" ] && continue
    send "$ref" REFUNDED ""
    count=$((count + 1))
  done <<< "$refs"

  # r.status, qualified: ticket_order has one too, and unqualified it is ambiguous - which
  # Postgres says at execution rather than when the query is written.
  printf '\n\033[1mrefunds on this event now\033[0m\n'
  query "select r.status || ': ' || count(*) from refund r
           join ticket_order o on o.id = r.order_id
          where o.event_id = '$event'
          group by r.status
          order by r.status" | sed 's/^/  /'
}

if [ "${1:-}" = "--event" ]; then
  [ -n "${2:-}" ] || usage
  settle_event "$2"
  exit 0
fi

order=${1:-}
mode=${2:-}
reason=${3:-The provider refused the refund.}
[ -n "$order" ] || usage

# The provider's handle for the reversal, read from the database rather than asked for: a real
# provider would have kept it, and this stands in for one.
provider_ref=$(query "select provider_ref from refund
                       where order_id = '$order'
                         and status = 'REFUND_PENDING'
                         and provider_ref is not null
                       order by created_at desc limit 1" | head -1 | xargs)

if [ -z "$provider_ref" ]; then
  echo "No refund is waiting on the provider for order $order." >&2
  echo "Start one from the event's orders page first - a refund is asked for by an owner or" >&2
  echo "a manager, never by the buyer (requirements/008 criterion 1)." >&2
  exit 1
fi

status=REFUNDED
failure=""
if [ "$mode" = "--fail" ]; then
  status=REFUND_FAILED
  # The reason is kept and shown, because a refund that failed is money the platform is still
  # holding and "it failed" with no cause is a dead end.
  failure="$reason"
fi

printf '\033[1msettling the refund on order %s as %s\033[0m\n' "${order:0:8}" "$status"
send "$provider_ref" "$status" "$failure"

printf '\n\033[1mthe order now\033[0m\n'
query "select status || '  refund_required=' || refund_required from ticket_order where id = '$order'" \
  | sed 's/^/  /'

printf '\033[1mthe refund\033[0m\n'
query "select status || coalesce('  ' || failure_reason, '') from refund
        where order_id = '$order' order by created_at desc limit 1" | sed 's/^/  /'

# Criterion 5: a refunded seat goes back on sale only while there is still a sale to go back
# into. On a cancelled Event it stays sold and that is correct, so this says which it is rather
# than printing "sold" and leaving somebody to wonder whether the refund half worked.
printf '\033[1mits seats\033[0m\n'
query "select string_agg(s.label || case when s.sold_at is null then ' (back on sale)'
                                         when e.status <> 'PUBLISHED'
                                              then ' (still sold - the event is ' ||
                                                   lower(e.status) || ', so nothing goes back on sale)'
                                         else ' (still sold)' end, ', ' order by s.label)
         from order_seat os
         join event_seat s on s.id = os.event_seat_id
         join ticket_order o on o.id = os.order_id
         join event e on e.id = o.event_id
        where os.order_id = '$order'" | sed 's/^/  /'
