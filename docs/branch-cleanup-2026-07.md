# 🧹 Чистка веток (2026-07-16) — проверено, безопасно удалять

> Проверка: у каждой ветки ниже **0 уникальных коммитов** относительно `release-2026-07`
> (или содержимое явно перенесено: driver-payouts → бэкенд выплат; partner-coupons → e2e-тест;
> promo-codes → переводы; docs/* и feature-roadmap → файлы в docs/). Потерь нет.
>
> ⚠️ НЕ УДАЛЯТЬ: `main`, `release-2026-07` (PR #73), `feat/webapp-pwa` (PR #75),
> `feat/payments-ui` — удалить ТОЛЬКО после переноса PayTripCard (см. tasks.md),
> `claude/github-workflow-offline-wq04l0` (служебная ветка сессии).
>
> Средa Claude не имеет права удалять удалённые ветки — выполни локально одной командой:

```bash
git push origin --delete \
  claude/audit-backend claude/audit-ui claude/feat-base claude/feature-roadmap \
  docs/business-logic docs/courier-plan docs/merge-guide docs/monetization-plan docs/product-plan \
  feat/advertiser-cabinet feat/anti-fraud feat/audit-tails feat/backups-dr feat/boost-yookassa \
  feat/ci-hardening feat/courier feat/courier-c2 feat/courier-c3 feat/courier-c4 feat/date-filter \
  feat/driver-checks feat/driver-confirm-booking feat/driver-debt feat/driver-payouts \
  feat/driver-schedule feat/edit-ride-request feat/geo-catalog feat/honesty-fixes \
  feat/income-calculator feat/instant-order feat/instant-order-ui feat/invite-drivers \
  feat/launch-extras feat/launch-tools feat/media-s3 feat/medical-partner feat/mode-switch \
  feat/my-stats feat/notification-center feat/observability feat/offline-trip-pass \
  feat/parcel-delivery feat/partner-coupons feat/payment-agreement feat/payments-ledger \
  feat/polish-tails feat/promo-codes feat/quality-ladder feat/rate-limit \
  feat/reviews-driver-profile feat/ride-cancel-complete feat/ride-history feat/route-watch \
  feat/scale-ops feat/seasonal-events feat/share-ride-link feat/tariffs-support \
  feat/taxi-gate feat/taxi-money-rules feat/taxi-polish feat/taxi-polish-2 feat/taxi2-base \
  feat/trip-live-link feat/trust-badges feat/trust-levels feat/trust-ui \
  feat/village-pickup-points feat/winter-safety feat/women-driver feat/work-hours \
  release-candidate release-taxi
```

После `feat/payments-ui` (когда PayTripCard в release): `git push origin --delete feat/payments-ui`.
