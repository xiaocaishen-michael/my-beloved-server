# Changelog

## 0.1.0 (2026-04-28)


### Features

* **shared:** add Bucket4j rate-limit framework (ADR-0011) ([#28](https://github.com/xiaocaishen-michael/my-beloved-server/issues/28)) ([4842e53](https://github.com/xiaocaishen-michael/my-beloved-server/commit/4842e53e00e3b94f6ad1fe4c480a4cb2db8f1ada))
* **shared:** add Observability baseline — JSON logging, MDC traceId, ProblemDetail ([#27](https://github.com/xiaocaishen-michael/my-beloved-server/issues/27)) ([8cbf6b4](https://github.com/xiaocaishen-michael/my-beloved-server/commit/8cbf6b489de1c925d9db75694cd65ca40328feb1))


### Bug Fixes

* **ci:** force release-please first release to 0.1.0 via release-as ([#33](https://github.com/xiaocaishen-michael/my-beloved-server/issues/33)) ([639dde9](https://github.com/xiaocaishen-michael/my-beloved-server/commit/639dde9d552c6b87f4bdf9b7c66c6793343c27aa))
* **ci:** rename commitlint config to .mjs and pin hadolint-action to v3.3.0 ([#26](https://github.com/xiaocaishen-michael/my-beloved-server/issues/26)) ([aa8ad0b](https://github.com/xiaocaishen-michael/my-beloved-server/commit/aa8ad0b7b7dfadcf0dc61a14247c58ff2ebe3609))
* **ci:** reset release-please manifest to 0.0.0 (Plan A) ([#36](https://github.com/xiaocaishen-michael/my-beloved-server/issues/36)) ([fb95c6f](https://github.com/xiaocaishen-michael/my-beloved-server/commit/fb95c6fb813aab47635aa86fa1c8a5b7358ca3a5))
* **ci:** set GH_REPO env so gh CLI works in release-please workflow ([#38](https://github.com/xiaocaishen-michael/my-beloved-server/issues/38)) ([df1bc33](https://github.com/xiaocaishen-michael/my-beloved-server/commit/df1bc33c3c6bbfb7c57d958d3a20c0164f70cc41))
* **ci:** set release-please starter version to 0.0.1 so first release is v0.1.0 ([#29](https://github.com/xiaocaishen-michael/my-beloved-server/issues/29)) ([f082119](https://github.com/xiaocaishen-michael/my-beloved-server/commit/f08211912186aa5e98c083263bbc6d2483f8c7d8))


### Maintenance

* **account:** scaffold mbw-account module skeleton (DDD 5-layer + spec/) ([#21](https://github.com/xiaocaishen-michael/my-beloved-server/issues/21)) ([096bec7](https://github.com/xiaocaishen-michael/my-beloved-server/commit/096bec796b45488bf06dd39cfd1fa5def032c383))
* **ci:** auto-merge release-please Snapshot PRs only, keep Release PRs manual ([#35](https://github.com/xiaocaishen-michael/my-beloved-server/issues/35)) ([2788bd9](https://github.com/xiaocaishen-michael/my-beloved-server/commit/2788bd92d83389c17ff7b50fbed9b69edf933610))
* **deps:** bump the github-actions group with 4 updates ([#18](https://github.com/xiaocaishen-michael/my-beloved-server/issues/18)) ([e6ea84f](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e6ea84f92ea7e384620ec2e931a7460ed202c9e5))
* **deps:** bump the lint-tools group with 4 updates ([#20](https://github.com/xiaocaishen-michael/my-beloved-server/issues/20)) ([4ee078d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/4ee078da928c924ecd4b4b9519bad913419011b4))
* **deps:** bump the spring group across 1 directory with 3 updates ([#19](https://github.com/xiaocaishen-michael/my-beloved-server/issues/19)) ([3de37c0](https://github.com/xiaocaishen-michael/my-beloved-server/commit/3de37c09ea9b69d9f85fb2c6d5a7bdd63b752dbd))
* **repo:** add Dockerfile + dev compose + Trivy fs/image scans in CI ([#23](https://github.com/xiaocaishen-michael/my-beloved-server/issues/23)) ([0762beb](https://github.com/xiaocaishen-michael/my-beloved-server/commit/0762beb344572a2437719c0cafe2747c6b74a077))
* **repo:** add init baseline files (gitignore, editorconfig, security, gitleaks hook) ([#4](https://github.com/xiaocaishen-michael/my-beloved-server/issues/4)) ([9a11f0c](https://github.com/xiaocaishen-michael/my-beloved-server/commit/9a11f0c75fef8ec00e7be447fb9a7e0f614cb928))
* **repo:** add Maven Enforcer + hadolint/actionlint/markdownlint/commitlint baseline ([#25](https://github.com/xiaocaishen-michael/my-beloved-server/issues/25)) ([26e5482](https://github.com/xiaocaishen-michael/my-beloved-server/commit/26e5482ff4b0255dca43392b7c9a2dcf3f11ae72))
* **repo:** add PR/Issue templates, JaCoCo 60% gate and DB migration rules ([#24](https://github.com/xiaocaishen-michael/my-beloved-server/issues/24)) ([a8dfa7c](https://github.com/xiaocaishen-michael/my-beloved-server/commit/a8dfa7c2ed725a01f680721c53cd76c4c169104d))
* **repo:** add Spotless + Checkstyle to lefthook pre-commit, full verify on pre-push ([#22](https://github.com/xiaocaishen-michael/my-beloved-server/issues/22)) ([cfef007](https://github.com/xiaocaishen-michael/my-beloved-server/commit/cfef007e5c1526ce808e0ca8e11b7ba578094617))
* **repo:** bump pom version to 0.1.0-SNAPSHOT to unlock release-please flow ([#31](https://github.com/xiaocaishen-michael/my-beloved-server/issues/31)) ([bf8177a](https://github.com/xiaocaishen-michael/my-beloved-server/commit/bf8177a70d632925e0cc9288916225680f661e6d))
* **repo:** scaffold engineering baseline (Maven + Spring Boot 3.5 + Modulith + lint + CI + spec-kit) ([#5](https://github.com/xiaocaishen-michael/my-beloved-server/issues/5)) ([ceabca8](https://github.com/xiaocaishen-michael/my-beloved-server/commit/ceabca85445e4e44c40c36486546beb663660b03))
* **repo:** tune Dependabot grouping + release-please changelog sections ([#17](https://github.com/xiaocaishen-michael/my-beloved-server/issues/17)) ([e3405cb](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e3405cbb90057de37c62b617db95bb1f1f254452))
