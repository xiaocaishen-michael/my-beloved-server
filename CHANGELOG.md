# Changelog

## [0.2.0](https://github.com/xiaocaishen-michael/my-beloved-server/compare/v0.1.0...v0.2.0) (2026-05-02)


### Features

* **account:** add Account aggregate root + AccountStateMachine ([#59](https://github.com/xiaocaishen-michael/my-beloved-server/issues/59)) ([9af5935](https://github.com/xiaocaishen-michael/my-beloved-server/commit/9af59358fcd9dd81840e1e08845897fcb80a921c))
* **account:** add AliyunSmsClient with Resilience4j retry (T1b) ([#72](https://github.com/xiaocaishen-michael/my-beloved-server/issues/72)) ([4bd4e7b](https://github.com/xiaocaishen-michael/my-beloved-server/commit/4bd4e7b7767a61df51524b89bb3a45814c54575f))
* **account:** add BCryptPasswordHasher + JwtTokenIssuer + JwtProperties ([#64](https://github.com/xiaocaishen-michael/my-beloved-server/issues/64)) ([2abca86](https://github.com/xiaocaishen-michael/my-beloved-server/commit/2abca86e8d1b022a4908d7a3e44a76c8775d5b9d))
* **account:** add Credential sealed interface + Phone/Password records ([#57](https://github.com/xiaocaishen-michael/my-beloved-server/issues/57)) ([9c4db98](https://github.com/xiaocaishen-michael/my-beloved-server/commit/9c4db98f291d59c7d8a1145c51dea0d546b61209))
* **account:** add domain repository contracts + ArchUnit boundary test ([#60](https://github.com/xiaocaishen-michael/my-beloved-server/issues/60)) ([58f505c](https://github.com/xiaocaishen-michael/my-beloved-server/commit/58f505c6c69cfc48b98780971f77b16edeaccae7))
* **account:** add domain value objects + AccountStatus enum ([#56](https://github.com/xiaocaishen-michael/my-beloved-server/issues/56)) ([bec5e28](https://github.com/xiaocaishen-michael/my-beloved-server/commit/bec5e28e4b393baf025b992ad1b427ee02e3afa4))
* **account:** add JPA persistence layer + Testcontainers PG IT ([#62](https://github.com/xiaocaishen-michael/my-beloved-server/issues/62)) ([551406a](https://github.com/xiaocaishen-michael/my-beloved-server/commit/551406a20d0c8b80b9f931c8441572458beaf1c1))
* **account:** add MockSmsCodeSender (ADR-0013 M1 邮件 mock) ([#76](https://github.com/xiaocaishen-michael/my-beloved-server/issues/76)) ([f349711](https://github.com/xiaocaishen-michael/my-beloved-server/commit/f349711cc8f184ed1793574a9090f10437305c82))
* **account:** add PhonePolicy + PasswordPolicy domain services ([#58](https://github.com/xiaocaishen-michael/my-beloved-server/issues/58)) ([07a2f82](https://github.com/xiaocaishen-michael/my-beloved-server/commit/07a2f828f1014c1eba2d9cdef350d0756263d58a))
* **account:** add RedisSmsCodeService impl + LoggingSmsClient stub (T2b) ([#69](https://github.com/xiaocaishen-michael/my-beloved-server/issues/69)) ([d38824b](https://github.com/xiaocaishen-michael/my-beloved-server/commit/d38824b1bc3b709786350fcb215b67c2c78b3164))
* **account:** add RedisVerificationCodeRepository with Lua atomicity ([#63](https://github.com/xiaocaishen-michael/my-beloved-server/issues/63)) ([c0f10db](https://github.com/xiaocaishen-michael/my-beloved-server/commit/c0f10db21b8ec52725670e17d1da35bcbbe3ee6a))
* **account:** add register controller + exception advice ([#68](https://github.com/xiaocaishen-michael/my-beloved-server/issues/68)) ([4f1be13](https://github.com/xiaocaishen-michael/my-beloved-server/commit/4f1be13b05f526ba25902da4b5f695d54e7e8bb8))
* **account:** add RegisterByPhoneUseCase ([#67](https://github.com/xiaocaishen-michael/my-beloved-server/issues/67)) ([67e4c42](https://github.com/xiaocaishen-michael/my-beloved-server/commit/67e4c421cb44abff026d3cfa5e1a2b27484bc22b))
* **account:** add RequestSmsCodeUseCase ([#66](https://github.com/xiaocaishen-michael/my-beloved-server/issues/66)) ([aec449b](https://github.com/xiaocaishen-michael/my-beloved-server/commit/aec449b0ad2e1d459365e6c7b208b6bec114a5ae))
* **account:** add SmsClient interface in mbw-shared.api.sms ([#54](https://github.com/xiaocaishen-michael/my-beloved-server/issues/54)) ([1553af1](https://github.com/xiaocaishen-michael/my-beloved-server/commit/1553af1d234bdcf10651b393a75d77f6ebee5f98))
* **account:** add SmsCodeService interface + AttemptOutcome record ([#55](https://github.com/xiaocaishen-michael/my-beloved-server/issues/55)) ([e18ab8a](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e18ab8a6c8fa1595cf79c6c2491c9662cf6f1422))
* **account:** add TimingDefenseExecutor.executeInConstantTime ([#65](https://github.com/xiaocaishen-michael/my-beloved-server/issues/65)) ([49feec8](https://github.com/xiaocaishen-michael/my-beloved-server/commit/49feec868ebab13edef2b8614cfac42fff9f3c5e))
* **account:** add V2 Flyway migration for register-by-phone ([#61](https://github.com/xiaocaishen-michael/my-beloved-server/issues/61)) ([df26699](https://github.com/xiaocaishen-michael/my-beloved-server/commit/df266993a9a57be312f5daec1a1f04772f66c917))
* **account:** impl login-by-phone-sms + login-by-password (M1.2 Phase 1.1+1.2) ([#98](https://github.com/xiaocaishen-michael/my-beloved-server/issues/98)) ([8458e00](https://github.com/xiaocaishen-michael/my-beloved-server/commit/8458e009ac5ec26fd12414fc752dfa09538c4c18))
* **account:** replace SMTP MockSmsCodeSender with Resend EmailSender ([#78](https://github.com/xiaocaishen-michael/my-beloved-server/issues/78)) ([ec0abba](https://github.com/xiaocaishen-michael/my-beloved-server/commit/ec0abba9c70a4a2f686e92df0a4bede0d7617e57))
* **auth:** impl logout-all (M1.2 Phase 1.4) ([#101](https://github.com/xiaocaishen-michael/my-beloved-server/issues/101)) ([1b33f7e](https://github.com/xiaocaishen-michael/my-beloved-server/commit/1b33f7e2fdb053e719525ae8663b08c7c7c0717b))
* **ci:** Phase 4 build-image workflow → push to Aliyun ACR ([#86](https://github.com/xiaocaishen-michael/my-beloved-server/issues/86)) ([6f5ec1f](https://github.com/xiaocaishen-michael/my-beloved-server/commit/6f5ec1f237833d494bfb79c1d0e345bc80ad4664))
* **ci:** Phase 4 deploy.yml — auto deploy to ECS via SSH ([#91](https://github.com/xiaocaishen-michael/my-beloved-server/issues/91)) ([d697a71](https://github.com/xiaocaishen-michael/my-beloved-server/commit/d697a716f19634ae399916abb55d5aa00f70edf3))
* **deploy:** add A-Tight v2 single-node compose + runbook ([#80](https://github.com/xiaocaishen-michael/my-beloved-server/issues/80)) ([e136666](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e136666d5b7cac2ca9187ef394a637de8eed70e5))
* **deploy:** split prod compose into app + data, prep A-Split topology ([#75](https://github.com/xiaocaishen-michael/my-beloved-server/issues/75)) ([bbc391f](https://github.com/xiaocaishen-michael/my-beloved-server/commit/bbc391fa27381ed82fd2d09cda4e8e5229f56cc1))
* **deploy:** use Aliyun's default `admin` user instead of creating `mbw` ([#82](https://github.com/xiaocaishen-michael/my-beloved-server/issues/82)) ([af31dd2](https://github.com/xiaocaishen-michael/my-beloved-server/commit/af31dd273e5d3e7e4b285346f00200f894a4c1ef))
* **shared:** migrate RateLimitService to bucket4j-redis backend (T0) ([#53](https://github.com/xiaocaishen-michael/my-beloved-server/issues/53)) ([f76fd05](https://github.com/xiaocaishen-michael/my-beloved-server/commit/f76fd059617c899d5c56e581b540b32716632477))


### Bug Fixes

* **ci:** ACR endpoint — Personal Edition uses crpi-&lt;id&gt;.cn-shanghai.personal ([#87](https://github.com/xiaocaishen-michael/my-beloved-server/issues/87)) ([7286416](https://github.com/xiaocaishen-michael/my-beloved-server/commit/728641637262d6025be7e0dffc631012f6815beb))
* **ci:** ACR namespace mbw → mbw_xcs (实际控制台命名空间) ([#89](https://github.com/xiaocaishen-michael/my-beloved-server/issues/89)) ([fb8e31b](https://github.com/xiaocaishen-michael/my-beloved-server/commit/fb8e31bdf86972868725005668df539de51896e1))
* **ci:** deploy.yml git fetch retry 3x for unstable international link ([#92](https://github.com/xiaocaishen-michael/my-beloved-server/issues/92)) ([e46de23](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e46de23e089f379fe1df7b1e4d8a9591ea27eb6b))
* **ci:** tighten lefthook commit-msg to total-header ≤ 100 chars ([#49](https://github.com/xiaocaishen-michael/my-beloved-server/issues/49)) ([cced0f7](https://github.com/xiaocaishen-michael/my-beloved-server/commit/cced0f7e4f71c047cb8a5633f7f163c30d2c8216))
* **deploy:** backup-pg.sh defaults match M1 A-Tight v2 + runbook ossutil flow ([#85](https://github.com/xiaocaishen-michael/my-beloved-server/issues/85)) ([3daa52f](https://github.com/xiaocaishen-michael/my-beloved-server/commit/3daa52fd8b548f9d7dcc627acaabf9bbbacf892a))
* **deploy:** bootstrap.sh — skip TUI prompt + auto-assign user UID ([#81](https://github.com/xiaocaishen-michael/my-beloved-server/issues/81)) ([90b33e8](https://github.com/xiaocaishen-michael/my-beloved-server/commit/90b33e84ab773c4ea861f9480364508a4b2e7f8f))
* **deploy:** tight role skips ufw on Aliyun SWAS (incompat) ([#83](https://github.com/xiaocaishen-michael/my-beloved-server/issues/83)) ([036d114](https://github.com/xiaocaishen-michael/my-beloved-server/commit/036d1149998f78b20f92f8b087f80cb208dc3ac8))
* **infra:** @Autowired on multi-ctor SmsClient/EmailClient (prod boot fix) ([#84](https://github.com/xiaocaishen-michael/my-beloved-server/issues/84)) ([e62d964](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e62d964fa4b8428b71b87c910c1bbb0dd254cd2e))


### Performance Improvements

* **ci:** remove redundant mvn package + JDK setup from build-image workflow ([#90](https://github.com/xiaocaishen-michael/my-beloved-server/issues/90)) ([4ad519d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/4ad519da4111385d073077cff6653cf7f95cff23))


### Maintenance

* **build:** skip @Tag(slow) tests by default + fix surefire/failsafe overlap ([#77](https://github.com/xiaocaishen-michael/my-beloved-server/issues/77)) ([a950d96](https://github.com/xiaocaishen-michael/my-beloved-server/commit/a950d9616b5cf2bdd092f007df48e26526eae425))
* **ci:** disable trivy-image scan during early dev (re-enable pre-M3) ([#43](https://github.com/xiaocaishen-michael/my-beloved-server/issues/43)) ([1199bd4](https://github.com/xiaocaishen-michael/my-beloved-server/commit/1199bd470cf8064616963c07e33b34039de5b126))
* **ci:** remove release-as override now that v0.1.0 is tagged ([#41](https://github.com/xiaocaishen-michael/my-beloved-server/issues/41)) ([a034701](https://github.com/xiaocaishen-michael/my-beloved-server/commit/a03470126110b4512bd26da940f9a8614bda9f09))
* **ci:** wire release-please-action to fine-grained PAT for CI triggering ([#42](https://github.com/xiaocaishen-michael/my-beloved-server/issues/42)) ([a693ae6](https://github.com/xiaocaishen-michael/my-beloved-server/commit/a693ae642434921cdaa874f0be0e18e13fc2a55b))
* **main:** release 0.1.1-SNAPSHOT ([#39](https://github.com/xiaocaishen-michael/my-beloved-server/issues/39)) ([87636b5](https://github.com/xiaocaishen-michael/my-beloved-server/commit/87636b531a1436483d015055b431f2c9320a3285))

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
