# 🗑 Удалённые ветки — точка возврата (2026-08-06)

> Ветки удалены после построчной и посмысловой проверки: их содержимое уже в `main`
> (переписанное заново, поэтому git не видел родства) либо перенесено в релизную ветку.
> Разбор — `decisions.md`, запись «Аудит 19 «перекрытых» веток».

**Вернуть любую:** `git push origin <номер>:refs/heads/<имя>` — номер из таблицы ниже.
Коммиты живут в репозитории ещё какое-то время после удаления ветки.

| Ветка | Номер последнего коммита | Дата |
|---|---|---|
| `claude/letters-countdown-site-d4ux6n` | `9fc39581d888c0432c154b0ae89abdfb21ebaf54` | 2026-08-05 |
| `claude/mimic-taxi-apps-analysis-q9e7dc` | `b9da66145c6a0f43d3710c7b7d15697a056fe159` | 2026-07-30 |
| `claude/app-bad-scenarios-protection-tdzr7m` | `2505346a8b74439ba648751c7f00ba831ab432d4` | 2026-07-24 |
| `claude/yandex-pro-plan` | `b9409f12139627dc6928df88e26ee0f0ddd43be5` | 2026-07-21 |
| `claude/pr88-safety-p3` | `178d6d32df326df89b7d0d6bdbd6b2b36178b7a0` | 2026-07-21 |
| `claude/pr88-safety-fixes` | `f3d9b1d854718d405d291eea94c9a24ab852b68a` | 2026-07-20 |
| `claude/notification-fixes` | `e3645543b705bdc27605561129011351ee7215eb` | 2026-07-20 |
| `claude/notification-client-android` | `f5cf7fbf50fd31baf95c9e6a7a93a64cdec562d6` | 2026-07-20 |
| `claude/approval-4ysxqg` | `a2970d0c5e6499b564e6ada1287d508d4f51ad17` | 2026-07-17 |
| `feat/promo-codes` | `ee63f6acc4befb621c58dd639baa62c2c44ef661` | 2026-07-12 |
| `docs/courier-plan` | `82776f7305d72ea66adbf89fd711bf3240a4aeee` | 2026-07-12 |
| `docs/monetization-plan` | `3d40c5b81ba3efa7294bc788c349c2eeefa2fbcb` | 2026-07-11 |
| `docs/merge-guide` | `a9505d70f27d12c1f111634c63c73148b52d19b1` | 2026-07-11 |
| `docs/business-logic` | `dfb5929966ed90b74c5ce0aa07e4ce101993cba4` | 2026-07-10 |
| `feat/driver-payouts` | `0c1bc07b1ad944e9499557c9fd553234870c3672` | 2026-07-09 |
| `feat/payments-ui` | `6c019c74adc2f624b627836e011c894247bd5246` | 2026-07-06 |
| `docs/product-plan` | `22eea9c5f08f2e50780368c62d0399c581b622de` | 2026-07-06 |
| `claude/github-workflow-offline-wq04l0` | `82d1d1e5d18146146fc6bdd5b52f91b161172bd3` | 2026-07-05 |
| `claude/feature-roadmap` | `711add1652ab47bc7e079baddc104e7a4ae065c6` | 2026-07-05 |

---

## Локальные ветки (только на ноутбуке Александра)

На сервере их не было — это рабочие копии закрытых задач. Содержимое каждой проверено:
оно есть в `main` либо в релизной ветке. Одна (`fix-push-register-idempotent`) держала
починку гонки при регистрации пуш-токена — код в `main` слово в слово, ветка не нужна.

| Ветка | Номер коммита | Где содержимое |
|---|---|---|
| `claude/admiring-ardinghelli-0505d0` | `2fbdd7125bbcc3c1fc82c4e2a9478761d3d123ad` | в main |
| `claude/adversarial-review-221b5c` | `19dd7947bb338f819db1e1c7817e441161ca2c77` | в main |
| `claude/affectionate-mcclintock-cddac8` | `72a182362aefd5a62c97cf13fab07d1387dd92d9` | в main |
| `claude/app-update-banner-6d05c6` | `a062bf392903687e18b83dcd7a56558e212cf221` | в релизной ветке |
| `claude/arrow-onroad` | `65fda42388e42b12e5670f45615e747d7f1321e6` | в main |
| `claude/bashkortostan-village-names-06408b` | `8e8ae1250ae66af575dca7bf542aa5462ac2e06a` | в релизной ветке |
| `claude/brave-germain-92c594` | `208e6851bcbbb2c334ccead51ee7f4628287cdb1` | в main |
| `claude/brave-northcutt-2d3a59` | `e7cf7cbe2b6d95b3671e68d1cdc6c3a7a4e61c0c` | в main |
| `claude/clever-joliot-db1aae` | `ab63facaebfb30c36d47ae93dab23c4016b25b2e` | в main |
| `claude/codex-plugin-cc-review-8d8dc0` | `9dd1fe236982f6148e8f1ff49c4cd27f76da836c` | в main |
| `claude/competent-khayyam-82f637` | `830a223bfc0c4803deace6c769413a23066c645e` | в main |
| `claude/consolidated-2026-07-02` | `98f6d96ee80ed5d964494fad0326ac448e1f4391` | в main |
| `claude/donate-counter` | `2073832c30185635b1582ca7e4af5681883b376c` | в main |
| `claude/driver-reject-reason` | `8091c9da9341123798a941b68bda840601fa4928` | в main |
| `claude/elastic-borg-0fa3f9` | `de17942586ffbb0bde72853ca4ac9e6e04d3dbb4` | в main |
| `claude/elated-murdock-ec817b` | `8c60d8936ff9ca0afbdee0d722b3320af3402b90` | в main |
| `claude/fix-leading-dot` | `81311501a35f0bbf521756f6c7ae097e7aeace1e` | в main |
| `claude/fix-push-register-idempotent` | `8021a3c93854608e7778dcf458f3ba1418e9ab44` | переписано в main (проверено построчно) |
| `claude/fix-smoking-filter` | `7b35148bd85e5052b5aa42c30dba683505536de7` | в main |
| `claude/fix-toggle-splash` | `40a59a77d8de7df4b6286052875654e4370abb47` | в main |
| `claude/inspiring-gagarin-58456c` | `9c9c618bb0b647bd0defb2ad492edba8bde6621f` | в main |
| `claude/intelligent-montalcini-73d69f` | `811d5195f0d3634754e53ba92fd869c929f33ff3` | в main |
| `claude/live-eta` | `706cf90b890996e099c218855ba195a1a3608c96` | в main |
| `claude/live-tracking-android-req` | `22ef53744d970bc74aff8f27a95e77c12149cee2` | в main |
| `claude/live-tracking-fg-service` | `ef3c2da9bff8a13b1d6bb46dd82d48964b2aa541` | в main |
| `claude/live-tracking-phase2` | `b095d76e11b1907c2e8c3cfb668506145103d387` | в main |
| `claude/live-tracking-ws-client` | `c1855e61812c5bb34436d3fc536c2e0273be1786` | в main |
| `claude/live-tracking-ws-location` | `71ac4a12fe2c1a60a468ad52879fe6896fc6a37e` | в main |
| `claude/loc-relay-fix` | `5c47f56556dd1402bfe1e5fadc957e3b3df99183` | в main |
| `claude/loc-socket-retry-fix` | `dba4d7e00acc66989294c12472ad555a67bc059c` | в main |
| `claude/lucid-lalande-6bd175` | `9dd1fe236982f6148e8f1ff49c4cd27f76da836c` | в main |
| `claude/map-autorefresh` | `d04b1310a34bf558618291737d990f128eb0a009` | в main |
| `claude/map-live-ws` | `3b0cc8ea55a256fa3df4c63518dde6d57fe7e136` | в main |
| `claude/map-request-card` | `ceb5ccb12b5e6e9d31d46e3a02104f0974421096` | в main |
| `claude/map-trips-audit` | `25bb65d0ecb3fc95a73652330baa24e0120eb25a` | в main |
| `claude/nearby-filters-all` | `0060d91053fd0fbb9f88dc86af9a31dad27d8f06` | в main |
| `claude/nostalgic-sanderson-d4c024` | `9dd1fe236982f6148e8f1ff49c4cd27f76da836c` | в main |
| `claude/notification-help-220e31` | `179d3745a055b4e5ff6bc89add499b6044c90a13` | в main |
| `claude/objective-shannon-5014c1` | `705e928e348641d3417ad7bb201934e42bf9b87f` | в main |
| `claude/parcel-fields` | `b3a605f72f659cd8bfdba1dfc65269ad1d3925a2` | в main |
| `claude/pedantic-allen-881a5c` | `fda4dcfbdb5b3c38ae7ba4601e6cc1a5b6fc48ba` | в main |
| `claude/peer-nav-arrow` | `c7e606da17f172cecca2542120b5240b839c01dd` | в main |
| `claude/peer-sim-demo` | `6697d498e41a6c427028a3a3d1ea28e93c425e77` | в main |
| `claude/peer-smooth` | `450cf25c823a1bb21db0e888963c0a404a992449` | в main |
| `claude/poll-lifecycle` | `e6e0977c399dddc64307b35372fe496a1dcfd8b2` | в main |
| `claude/real-trip-eta` | `ce38e075d54ea3f1785400e10510b3dd5b143c9d` | в main |
| `claude/registered-users-count-8a3f31` | `d1301592b097463e23e8cbd17372c3f7e432b063` | в main |
| `claude/request-prefs` | `9cadaade4d0bf19da2e41ea7d58bb348311b3e27` | в main |
| `claude/rm-hospital` | `5ee7eefb941df8c74b87578f5ab2be2b1abb8b37` | в main |
| `claude/route-adaptive` | `ca4333803ba063cafe8c0c63bf29abe15d99b777` | в main |
| `claude/route-eta` | `4f41e92c3426beec89ce4017431d1e7724f929c5` | в main |
| `claude/route-zoom-fix` | `db8647ca0360d01ce25ac29bedfc76287364368e` | в main |
| `claude/routes-phase1-crashfixes-prodsync` | `1b51c19d216e49bf62a5af588f9dc474eeb1b10f` | в main |
| `claude/silly-spence-56bf2f` | `be7b0e0c42f3207d729ca5e55e3663e4680c62bc` | в main |
| `claude/sim-tune` | `1bec2d3333debabdc1a6d4bab887d7d6dded5c13` | в main |
| `claude/sleepy-rhodes-419f4f` | `9440904ed44800e1e5e8e611a4cd43224fbb57e9` | в main |
| `claude/smooth-adaptive` | `428f8b4be2f75c1b6c62e6a7e513fdcd482d9763` | в main |
| `claude/status-watcher` | `48a780b09c16ae9f56bb72e40483adfb9118e4b3` | в main |
| `claude/tan-ata-repository-55a5be` | `0ab489ca3e86cc2880d39f4007d52cf852e25e34` | в main |
| `claude/taxi-courier-integration-test-72e3f5` | `dd966f90a1c76acae4aba3bc1ffce97e5be1c999` | в main |
| `claude/wizardly-wilbur-ebeb0b` | `208e6851bcbbb2c334ccead51ee7f4628287cdb1` | в main |
| `codex/login-hero-refresh` | `d0cdc35630899a40b808e0a2292cd7ca35be91fa` | в main |
| `feat/partner-ads` | `d86bda054b34bfc567d0b1f59bdb5822d0093a97` | в main |
