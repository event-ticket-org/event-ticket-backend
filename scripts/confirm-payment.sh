#!/usr/bin/env bash
#
# Acts as the payment provider, so an Order can reach paid on a laptop.
#
# requirements/005 criterion 3: an Order transitions to paid only on a provider confirmation,
# and the buyer returning to the site never confirms one. That is deliberate and it is what
# makes local development impossible without this - the browser can start a payment and then
# has nothing to wait for, for ever.
#
# What it sends is a real webhook, not a shortcut: the same path, the same body, and a real
# HMAC over the raw bytes, which FakePaymentProvider verifies in constant time before the
# payload is trusted. Nothing here reaches past the API to mark an Order paid, because a
# shortcut that did would stop exercising the code that matters - idempotency, the tenant a
# webhook has to adopt, and the seats it sells.
#
#   scripts/confirm-payment.sh <order-id>            confirm the latest attempt
#   scripts/confirm-payment.sh <order-id> --fail     tell it the payment failed
#   scripts/confirm-payment.sh <order-id> --twice    deliver the same webhook twice
#
set -euo pipefail

BASE=${BASE:-http://localhost:8080/api/v1}
SECRET=${FAKE_PAYMENT_SECRET:-fake-provider-shared-secret}
CONTAINER=${PG_CONTAINER:-event-ticket-backend-postgres-1}

order=${1:-}
mode=${2:-}

if [ -z "$order" ]; then
  echo "usage: $0 <order-id> [--fail|--twice]" >&2
  echo >&2
  echo "Find an order id in the browser's URL on the checkout page, or:" >&2
  echo "  docker exec $CONTAINER psql -U eventticket -d eventticket -c \\" >&2
  echo "    \"select id, status, total_amount from ticket_order order by created_at desc limit 5\"" >&2
  exit 2
fi

query() {
  docker exec "$CONTAINER" psql -U eventticket -d eventticket -tAc "$1" | tr -d '\r'
}

# The provider's own handle for the attempt. Reading it from the database rather than asking
# the buyer to copy it: a real provider would have kept it, and this stands in for one.
provider_ref=$(query "select provider_ref from payment_session
                       where order_id = '$order'
                       order by created_at desc limit 1" | head -1 | xargs)

if [ -z "$provider_ref" ]; then
  echo "No payment session for order $order." >&2
  echo "Start a payment in the browser first - the session is created when the buyer" >&2
  echo "chooses how to pay, not when the order is created." >&2
  exit 1
fi

status=PAID
if [ "$mode" = "--fail" ]; then
  status=FAILED
fi

send() {
  # A provider gives every delivery its own id, and that is what makes a repeat recognisable
  # as one (requirements/005 criterion 4). Passing the same id twice is the point of --twice.
  local delivery="$1"
  local body
  body=$(printf '{"eventId":"%s","providerRef":"%s","status":"%s"}' \
    "$delivery" "$provider_ref" "$status")

  # Signed over the raw bytes. A body parsed and re-serialised has a different signature, and
  # that mismatch only ever shows up against a real provider.
  local signature
  signature=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$SECRET" -hex \
    | sed 's/^.*= //')

  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/webhooks/payments/FAKE" \
    -H 'Content-Type: application/json' \
    -H "x-signature: $signature" \
    -d "$body")
  printf '  delivery %s -> HTTP %s\n' "${delivery:0:8}" "$code"
}

delivery_id=$(uuidgen | tr '[:upper:]' '[:lower:]')

printf '\033[1mconfirming order %s as %s\033[0m\n' "${order:0:8}" "$status"
send "$delivery_id"

if [ "$mode" = "--twice" ]; then
  # Criterion 4: the same webhook delivered twice produces one paid Order and one set of
  # Tickets. Both deliveries are acknowledged; only the first does anything.
  send "$delivery_id"
fi

printf '\n\033[1mthe order now\033[0m\n'
query "select status || '  refund_required=' || refund_required from ticket_order where id = '$order'" \
  | sed 's/^/  /'

printf '\033[1mtickets issued\033[0m\n'
query "select count(*) || ' ticket(s)' from ticket where order_id = '$order'" | sed 's/^/  /'
