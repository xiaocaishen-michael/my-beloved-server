# Changelog

## [0.4.0](https://github.com/xiaocaishen-michael/my-beloved-server/compare/v0.3.1...v0.4.0) (2026-05-15)


### Features

* **repo:** install api-types-sync preset (FW-2 Phase 2) ([#195](https://github.com/xiaocaishen-michael/my-beloved-server/issues/195)) ([4391332](https://github.com/xiaocaishen-michael/my-beloved-server/commit/4391332ef936479eb2bbffa3d43187144e07028b))
* **repo:** lefthook tasks-md-drift hook ([#190](https://github.com/xiaocaishen-michael/my-beloved-server/issues/190)) ([290a339](https://github.com/xiaocaishen-michael/my-beloved-server/commit/290a33942e3ad9eb36d235645b10eaa761dee743))


### Bug Fixes

* **ops:** nginx default_server expose /healthz so docker healthcheck flips to healthy ([#187](https://github.com/xiaocaishen-michael/my-beloved-server/issues/187)) ([9cc3813](https://github.com/xiaocaishen-michael/my-beloved-server/commit/9cc381392bddce4f8f8040aeec1bed99e8efde96))


### Maintenance

* **main:** release 0.3.2-SNAPSHOT ([#184](https://github.com/xiaocaishen-michael/my-beloved-server/issues/184)) ([a2e8fa5](https://github.com/xiaocaishen-michael/my-beloved-server/commit/a2e8fa55202cad20f13e274d1e2f7a5ba57f7fa9))
* **repo:** adopt context7-injection + task-closure presets via michael-speckit-presets ([#193](https://github.com/xiaocaishen-michael/my-beloved-server/issues/193)) ([9b107a6](https://github.com/xiaocaishen-michael/my-beloved-server/commit/9b107a6e344e787d558337def66c8e75fe3bedde))
* **repo:** commitlint footer-max 150 + lefthook tasks-md-drift regex `( |$)` 边界 fix ([#192](https://github.com/xiaocaishen-michael/my-beloved-server/issues/192)) ([276a789](https://github.com/xiaocaishen-michael/my-beloved-server/commit/276a789ae6eebc894cf47f9e45eed1ed50a80ac7))
* **repo:** path sweep — archive plan 26-05-14-witty-churning-tome.md → archive/26-05/ ([#194](https://github.com/xiaocaishen-michael/my-beloved-server/issues/194)) ([4f5253d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/4f5253df07df99d54bc059f4f54a4195d8bd0e3b))
* **repo:** rename spec/ → specs/ to align spec-kit core default ([#189](https://github.com/xiaocaishen-michael/my-beloved-server/issues/189)) ([6dfbc1e](https://github.com/xiaocaishen-michael/my-beloved-server/commit/6dfbc1e63abca8079e53884fe0ec6aa2bdb08b61))
* **repo:** spec.md → symlink + path align with meta canonical + spec-must-be-symlink hook ([#191](https://github.com/xiaocaishen-michael/my-beloved-server/issues/191)) ([2581255](https://github.com/xiaocaishen-michael/my-beloved-server/commit/25812557b6a30beea1c4f85ddeb0de06836b2ec3))
* **repo:** upgrade spec-kit 0.8.2.dev0 → 0.8.7 + reapply C1-C4 ([#188](https://github.com/xiaocaishen-michael/my-beloved-server/issues/188)) ([ef64cfd](https://github.com/xiaocaishen-michael/my-beloved-server/commit/ef64cfd6eb78f105698d79e44573ae5aebc136cf))

## [0.3.1](https://github.com/xiaocaishen-michael/my-beloved-server/compare/v0.3.0...v0.3.1) (2026-05-14)


### Bug Fixes

* **core:** dev-server.sh 加 -am 让副 worktree 首次跑找到 cross-module deps ([#177](https://github.com/xiaocaishen-michael/my-beloved-server/issues/177)) ([7bf4fdb](https://github.com/xiaocaishen-michael/my-beloved-server/commit/7bf4fdbff2ae9184ce4be403e49c1b6a92cc05f2))
* **core:** dev-server.sh 自 source .envrc 不依赖 direnv hook ([#181](https://github.com/xiaocaishen-michael/my-beloved-server/issues/181)) ([a4cde2e](https://github.com/xiaocaishen-michael/my-beloved-server/commit/a4cde2ed668c2dd5a41fa8232e087c93152b0c74))
* **repo:** pin runtime base to eclipse-temurin:21-jre-noble ([#180](https://github.com/xiaocaishen-michael/my-beloved-server/issues/180)) ([5ec6264](https://github.com/xiaocaishen-michael/my-beloved-server/commit/5ec62646634bd386f563b4cc9c54a1258fa798b7))


### Maintenance

* **core:** per-feature isolation infra (Redis db / Flyway / Hikari / CI lint) ([#175](https://github.com/xiaocaishen-michael/my-beloved-server/issues/175)) ([115a273](https://github.com/xiaocaishen-michael/my-beloved-server/commit/115a273e0d6e8428db1c9b030f025b8e0733b701))
* **main:** release 0.3.1-SNAPSHOT ([#162](https://github.com/xiaocaishen-michael/my-beloved-server/issues/162)) ([bfb96cc](https://github.com/xiaocaishen-michael/my-beloved-server/commit/bfb96cc355244d3dc88443563aaeb432b5427274))
* **ops:** archive A-Split materials & rename .env.app → .env.production ([#183](https://github.com/xiaocaishen-michael/my-beloved-server/issues/183)) ([e2800ce](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e2800cee7ce73a6184c73f30d160d9163d6644bc))
* **repo:** add path-scoped rules for Docker and API contract (O7) ([#172](https://github.com/xiaocaishen-michael/my-beloved-server/issues/172)) ([7eba3db](https://github.com/xiaocaishen-michael/my-beloved-server/commit/7eba3db9bd2cc0c3c76cfa61ad92408eac0ea1ab))
* **repo:** add skip_git_fetch toggle to deploy.yml ([#163](https://github.com/xiaocaishen-michael/my-beloved-server/issues/163)) ([ce4741b](https://github.com/xiaocaishen-michael/my-beloved-server/commit/ce4741b4cd24440a7e1897a1f275dcca24878aac))
* **repo:** dependabot ignore netty/ip2region semver-major ([#179](https://github.com/xiaocaishen-michael/my-beloved-server/issues/179)) ([535dd4d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/535dd4df24382ddde0a2dfe0de8012b72ddf3332))
* **repo:** path-scope checkstyle rationale (out of meta always-load) ([#173](https://github.com/xiaocaishen-michael/my-beloved-server/issues/173)) ([cc88881](https://github.com/xiaocaishen-michael/my-beloved-server/commit/cc88881c27a9a32fdda6eaf2ebc6eb3a83d0f1ff))
* **repo:** spotless:check → auto-apply in pre-commit hook (O6) ([#169](https://github.com/xiaocaishen-michael/my-beloved-server/issues/169)) ([b328e5a](https://github.com/xiaocaishen-michael/my-beloved-server/commit/b328e5ad3cb65b95645f84c34af9e071b326647e))
* **repo:** spotless:check → auto-apply in pre-commit hook (O6) ([#171](https://github.com/xiaocaishen-michael/my-beloved-server/issues/171)) ([107e9f9](https://github.com/xiaocaishen-michael/my-beloved-server/commit/107e9f90b6908728b79bce3a049142b792d3cb0f))
* **repo:** wire realname env to compose + fix pepper env name ([#165](https://github.com/xiaocaishen-michael/my-beloved-server/issues/165)) ([dc0a095](https://github.com/xiaocaishen-michael/my-beloved-server/commit/dc0a09510819a21a7b664977c3f96ba2db0d1dbf))

## [0.3.0](https://github.com/xiaocaishen-michael/my-beloved-server/compare/v0.2.0...v0.3.0) (2026-05-10)


### Features

* **account:** dev-fixed SMS code env hatch + bump example email ([#146](https://github.com/xiaocaishen-michael/my-beloved-server/issues/146)) ([152fe62](https://github.com/xiaocaishen-michael/my-beloved-server/commit/152fe623f7d12377d233178563c865a7d19ca922))
* **account:** device-management T8 real adapter + T15/T16/T17 ITs ([#154](https://github.com/xiaocaishen-michael/my-beloved-server/issues/154)) ([58b380d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/58b380da67be84a28838ccbd7f36c413a5aff984))
* **account:** extend dev-fixed-code env to deletion + cancel-deletion flows ([#148](https://github.com/xiaocaishen-michael/my-beloved-server/issues/148)) ([22c2135](https://github.com/xiaocaishen-michael/my-beloved-server/commit/22c21352f36ae87690aab2ebe46b18cad8c982fb))
* **account:** impl device-management (M1.X / T0-T14, T18) ([#153](https://github.com/xiaocaishen-michael/my-beloved-server/issues/153)) ([8e49e97](https://github.com/xiaocaishen-michael/my-beloved-server/commit/8e49e97275c4383ce6fb71ebcb78e43ff15e9920))
* **account:** realname-verification PR-1 — domain + repo (T0-T7) ([#155](https://github.com/xiaocaishen-michael/my-beloved-server/issues/155)) ([ce35b0d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/ce35b0d9a513aa87214795613906e654f895128a))
* **account:** realname-verification PR-2 — infrastructure (T8-T11) ([#157](https://github.com/xiaocaishen-michael/my-beloved-server/issues/157)) ([456f228](https://github.com/xiaocaishen-michael/my-beloved-server/commit/456f2282ff9205ca8f98a7f70b8d6a2836a34563))
* **account:** realname-verification PR-3 — application + web (T12-T16) ([#158](https://github.com/xiaocaishen-michael/my-beloved-server/issues/158)) ([13f11a5](https://github.com/xiaocaishen-michael/my-beloved-server/commit/13f11a5294a143619db6b6e52b08d1090a42f296))
* **core:** add ProdCorsConfig for Cloudflare Pages cross-origin ([#161](https://github.com/xiaocaishen-michael/my-beloved-server/issues/161)) ([5c2e9ee](https://github.com/xiaocaishen-michael/my-beloved-server/commit/5c2e9ee156784791ec58f317ddeb514a9f8c4794))


### Bug Fixes

* **account:** realname_profile.id_card_hash CHAR → VARCHAR — fix Hibernate boot validation ([#160](https://github.com/xiaocaishen-michael/my-beloved-server/issues/160)) ([b8cf2e5](https://github.com/xiaocaishen-michael/my-beloved-server/commit/b8cf2e5624c1ad689f304de375d26127c942d0a6))
* **account:** refresh_token.device_id NOT NULL — align schema/domain invariant ([#159](https://github.com/xiaocaishen-michael/my-beloved-server/issues/159)) ([43e603b](https://github.com/xiaocaishen-michael/my-beloved-server/commit/43e603b7c56fe3275c841ffde4061eec3b74f819))
* **repo:** bump netty BOM to 4.1.133.Final (CVE-2026-42583) ([#156](https://github.com/xiaocaishen-michael/my-beloved-server/issues/156)) ([f044c72](https://github.com/xiaocaishen-michael/my-beloved-server/commit/f044c72279aab43689c3c277c1abf0f682c71314))


### Maintenance

* **main:** release 0.2.1-SNAPSHOT ([#144](https://github.com/xiaocaishen-michael/my-beloved-server/issues/144)) ([76c71cd](https://github.com/xiaocaishen-michael/my-beloved-server/commit/76c71cd5511d24c5301c7898354bdacb78079a5a))
* **repo:** commitlint body-max-line-length 100 → 150 (统一三仓) ([#152](https://github.com/xiaocaishen-michael/my-beloved-server/issues/152)) ([b5fcdb3](https://github.com/xiaocaishen-michael/my-beloved-server/commit/b5fcdb348cee23d2982156bcb269ebdbfedc3d10))

## [0.2.0](https://github.com/xiaocaishen-michael/my-beloved-server/compare/v0.1.0...v0.2.0) (2026-05-07)


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
* **account:** anonymize-frozen-accounts core impl T0-T8 (M1.3) ([#137](https://github.com/xiaocaishen-michael/my-beloved-server/issues/137)) ([fafefdd](https://github.com/xiaocaishen-michael/my-beloved-server/commit/fafefdd617e46a7a62f891d189e5b22c2b9bac69))
* **account:** cancel-deletion impl (M1.3 / T0-T9) ([#134](https://github.com/xiaocaishen-michael/my-beloved-server/issues/134)) ([0595ef1](https://github.com/xiaocaishen-michael/my-beloved-server/commit/0595ef1a2eb1cbdc0bdd1b7bd465fff892c543b0))
* **account:** delete-account T0-T3+T6 — migrations, domain, events, commands ([4d85a32](https://github.com/xiaocaishen-michael/my-beloved-server/commit/4d85a3204d671d2f61e4f62c4b8cf3bb868033fa))
* **account:** expose phone in /me response (account-settings-shell prereq) ([#139](https://github.com/xiaocaishen-michael/my-beloved-server/issues/139)) ([b18b9e5](https://github.com/xiaocaishen-michael/my-beloved-server/commit/b18b9e56977592873e9918f43d102fb5b38a714b))
* **account:** expose-frozen-account-status (spec D, spec C 前置) ([#143](https://github.com/xiaocaishen-michael/my-beloved-server/issues/143)) ([89db8ed](https://github.com/xiaocaishen-michael/my-beloved-server/commit/89db8ed93b5c25276ced003049d6b25c6c612b32))
* **account:** impl account-profile T0-T8 (onboarding 信号 + displayName 维护) ([#127](https://github.com/xiaocaishen-michael/my-beloved-server/issues/127)) ([7a08b3b](https://github.com/xiaocaishen-michael/my-beloved-server/commit/7a08b3b4174edded5799088001323ef6f82c358d))
* **account:** impl login-by-phone-sms + login-by-password (M1.2 Phase 1.1+1.2) ([#98](https://github.com/xiaocaishen-michael/my-beloved-server/issues/98)) ([8458e00](https://github.com/xiaocaishen-michael/my-beloved-server/commit/8458e009ac5ec26fd12414fc752dfa09538c4c18))
* **account:** impl unified phone-SMS auth (per ADR-0016) ([#118](https://github.com/xiaocaishen-michael/my-beloved-server/issues/118)) ([0514460](https://github.com/xiaocaishen-michael/my-beloved-server/commit/051446072f9c427650ff46876f9fd038c5d944f8))
* **account:** make auth rate-limit configurable; relax in dev profile ([#126](https://github.com/xiaocaishen-michael/my-beloved-server/issues/126)) ([86928f4](https://github.com/xiaocaishen-michael/my-beloved-server/commit/86928f4e97e65ff822a56270f1f9e41a73ffe8b0))
* **account:** replace SMTP MockSmsCodeSender with Resend EmailSender ([#78](https://github.com/xiaocaishen-michael/my-beloved-server/issues/78)) ([ec0abba](https://github.com/xiaocaishen-michael/my-beloved-server/commit/ec0abba9c70a4a2f686e92df0a4bede0d7617e57))
* **account:** T10 AccountDeletionE2EIT + Clock constructor fix (delete-account M1.3) ([dbdc47e](https://github.com/xiaocaishen-michael/my-beloved-server/commit/dbdc47ed88c7f7a432157db3c7ac778bb1d48689))
* **account:** T11 AccountDeletionConcurrencyIT (delete-account M1.3) ([be85475](https://github.com/xiaocaishen-michael/my-beloved-server/commit/be85475b4504becab9e7fa04d81e907d6173b9c8))
* **account:** T12 CrossUseCaseEnumerationDefenseIT (delete-account M1.3) ([8d8173d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/8d8173de52e8af8f1ff5c06fbbde44515b92f264))
* **account:** T13 OpenAPI snapshot regen with deletion endpoints (M1.3) ([9dc78c7](https://github.com/xiaocaishen-michael/my-beloved-server/commit/9dc78c73051496cce57a1766b8a5d189a8fdca11))
* **account:** T4 AccountSmsCode domain model + JPA infra (delete-account M1.3) ([63c0050](https://github.com/xiaocaishen-michael/my-beloved-server/commit/63c005098041c1b286fcfa34a0d9fb3f3fcd9068))
* **account:** T5 AccountJpaEntity.freezeUntil + AccountMapper (delete-account M1.3) ([ddc7e23](https://github.com/xiaocaishen-michael/my-beloved-server/commit/ddc7e23d6c6ea04090ee01d30bf353428ee65e36))
* **account:** T7 SendDeletionCodeUseCase (delete-account M1.3) ([3b2e72a](https://github.com/xiaocaishen-michael/my-beloved-server/commit/3b2e72adfa207601a1c27f2fd0d994d0ea2a4a0b))
* **account:** T8 DeleteAccountUseCase + InvalidDeletionCodeException (delete-account M1.3) ([ebd7890](https://github.com/xiaocaishen-michael/my-beloved-server/commit/ebd7890c5fc0db325831fbb4212638b19c9a7479))
* **account:** T9 AccountDeletionController + DeleteAccountRequest (delete-account M1.3) ([f9f780c](https://github.com/xiaocaishen-michael/my-beloved-server/commit/f9f780c81f68192c560cd689212d8c0ee3eb3c6f))
* **auth:** impl logout-all (M1.2 Phase 1.4) ([#101](https://github.com/xiaocaishen-michael/my-beloved-server/issues/101)) ([1b33f7e](https://github.com/xiaocaishen-michael/my-beloved-server/commit/1b33f7e2fdb053e719525ae8663b08c7c7c0717b))
* **ci:** Phase 4 build-image workflow → push to Aliyun ACR ([#86](https://github.com/xiaocaishen-michael/my-beloved-server/issues/86)) ([6f5ec1f](https://github.com/xiaocaishen-michael/my-beloved-server/commit/6f5ec1f237833d494bfb79c1d0e345bc80ad4664))
* **ci:** Phase 4 deploy.yml — auto deploy to ECS via SSH ([#91](https://github.com/xiaocaishen-michael/my-beloved-server/issues/91)) ([d697a71](https://github.com/xiaocaishen-michael/my-beloved-server/commit/d697a716f19634ae399916abb55d5aa00f70edf3))
* **deploy:** add A-Tight v2 single-node compose + runbook ([#80](https://github.com/xiaocaishen-michael/my-beloved-server/issues/80)) ([e136666](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e136666d5b7cac2ca9187ef394a637de8eed70e5))
* **deploy:** split prod compose into app + data, prep A-Split topology ([#75](https://github.com/xiaocaishen-michael/my-beloved-server/issues/75)) ([bbc391f](https://github.com/xiaocaishen-michael/my-beloved-server/commit/bbc391fa27381ed82fd2d09cda4e8e5229f56cc1))
* **deploy:** use Aliyun's default `admin` user instead of creating `mbw` ([#82](https://github.com/xiaocaishen-michael/my-beloved-server/issues/82)) ([af31dd2](https://github.com/xiaocaishen-michael/my-beloved-server/commit/af31dd273e5d3e7e4b285346f00200f894a4c1ef))
* **repo:** add dev CORS config for localhost:8081 web client ([#104](https://github.com/xiaocaishen-michael/my-beloved-server/issues/104)) ([57645a0](https://github.com/xiaocaishen-michael/my-beloved-server/commit/57645a0f5c0ab75befaae61e6d482f829865cdc3))
* **repo:** self-enforce tasks.md closure in speckit-implement SKILL ([#133](https://github.com/xiaocaishen-michael/my-beloved-server/issues/133)) ([1b570c0](https://github.com/xiaocaishen-michael/my-beloved-server/commit/1b570c05a6a837f9104a16082829dd5303b9bf3a))
* **shared:** migrate RateLimitService to bucket4j-redis backend (T0) ([#53](https://github.com/xiaocaishen-michael/my-beloved-server/issues/53)) ([f76fd05](https://github.com/xiaocaishen-michael/my-beloved-server/commit/f76fd059617c899d5c56e581b540b32716632477))


### Bug Fixes

* **account:** MockSmsCodeSender subject 加 UUID 后缀避开 mailbox dedup ([#106](https://github.com/xiaocaishen-michael/my-beloved-server/issues/106)) ([e252485](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e252485bea22c96587c11075bcb64f01f65f823d))
* **ci:** ACR endpoint — Personal Edition uses crpi-&lt;id&gt;.cn-shanghai.personal ([#87](https://github.com/xiaocaishen-michael/my-beloved-server/issues/87)) ([7286416](https://github.com/xiaocaishen-michael/my-beloved-server/commit/728641637262d6025be7e0dffc631012f6815beb))
* **ci:** ACR namespace mbw → mbw_xcs (实际控制台命名空间) ([#89](https://github.com/xiaocaishen-michael/my-beloved-server/issues/89)) ([fb8e31b](https://github.com/xiaocaishen-michael/my-beloved-server/commit/fb8e31bdf86972868725005668df539de51896e1))
* **ci:** deploy.yml git fetch retry 3x for unstable international link ([#92](https://github.com/xiaocaishen-michael/my-beloved-server/issues/92)) ([e46de23](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e46de23e089f379fe1df7b1e4d8a9591ea27eb6b))
* **ci:** tighten lefthook commit-msg to total-header ≤ 100 chars ([#49](https://github.com/xiaocaishen-michael/my-beloved-server/issues/49)) ([cced0f7](https://github.com/xiaocaishen-michael/my-beloved-server/commit/cced0f7e4f71c047cb8a5633f7f163c30d2c8216))
* **deploy:** backup-pg.sh defaults match M1 A-Tight v2 + runbook ossutil flow ([#85](https://github.com/xiaocaishen-michael/my-beloved-server/issues/85)) ([3daa52f](https://github.com/xiaocaishen-michael/my-beloved-server/commit/3daa52fd8b548f9d7dcc627acaabf9bbbacf892a))
* **deploy:** bootstrap.sh — skip TUI prompt + auto-assign user UID ([#81](https://github.com/xiaocaishen-michael/my-beloved-server/issues/81)) ([90b33e8](https://github.com/xiaocaishen-michael/my-beloved-server/commit/90b33e84ab773c4ea861f9480364508a4b2e7f8f))
* **deploy:** tight role skips ufw on Aliyun SWAS (incompat) ([#83](https://github.com/xiaocaishen-michael/my-beloved-server/issues/83)) ([036d114](https://github.com/xiaocaishen-michael/my-beloved-server/commit/036d1149998f78b20f92f8b087f80cb208dc3ac8))
* **infra:** @Autowired on multi-ctor SmsClient/EmailClient (prod boot fix) ([#84](https://github.com/xiaocaishen-michael/my-beloved-server/issues/84)) ([e62d964](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e62d964fa4b8428b71b87c910c1bbb0dd254cd2e))
* **repo:** force bcprov-jdk18on 1.84 to remediate CVE-2026-5598 ([44cce42](https://github.com/xiaocaishen-michael/my-beloved-server/commit/44cce427c246a3259b590856375846ad27719f93))
* **repo:** unblock local E2E (default dev profile + 429 on pending SMS code) ([#124](https://github.com/xiaocaishen-michael/my-beloved-server/issues/124)) ([e30cbc7](https://github.com/xiaocaishen-michael/my-beloved-server/commit/e30cbc735f3432388d09e05718ccc2e30b971bec))


### Performance Improvements

* **ci:** remove redundant mvn package + JDK setup from build-image workflow ([#90](https://github.com/xiaocaishen-michael/my-beloved-server/issues/90)) ([4ad519d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/4ad519da4111385d073077cff6653cf7f95cff23))


### Maintenance

* **build:** skip @Tag(slow) tests by default + fix surefire/failsafe overlap ([#77](https://github.com/xiaocaishen-michael/my-beloved-server/issues/77)) ([a950d96](https://github.com/xiaocaishen-michael/my-beloved-server/commit/a950d9616b5cf2bdd092f007df48e26526eae425))
* **ci:** disable trivy-image scan during early dev (re-enable pre-M3) ([#43](https://github.com/xiaocaishen-michael/my-beloved-server/issues/43)) ([1199bd4](https://github.com/xiaocaishen-michael/my-beloved-server/commit/1199bd470cf8064616963c07e33b34039de5b126))
* **ci:** remove release-as override now that v0.1.0 is tagged ([#41](https://github.com/xiaocaishen-michael/my-beloved-server/issues/41)) ([a034701](https://github.com/xiaocaishen-michael/my-beloved-server/commit/a03470126110b4512bd26da940f9a8614bda9f09))
* **ci:** wire release-please-action to fine-grained PAT for CI triggering ([#42](https://github.com/xiaocaishen-michael/my-beloved-server/issues/42)) ([a693ae6](https://github.com/xiaocaishen-michael/my-beloved-server/commit/a693ae642434921cdaa874f0be0e18e13fc2a55b))
* **deps-dev:** bump org.wiremock:wiremock-standalone ([#114](https://github.com/xiaocaishen-michael/my-beloved-server/issues/114)) ([6508db2](https://github.com/xiaocaishen-michael/my-beloved-server/commit/6508db23499a2c89cf60f2176068eecb88e5b2a4))
* **deps:** bump com.aliyun:dysmsapi20170525 from 3.0.0 to 4.5.1 ([#110](https://github.com/xiaocaishen-michael/my-beloved-server/issues/110)) ([d31c7ba](https://github.com/xiaocaishen-michael/my-beloved-server/commit/d31c7bafe3f62f5ef4cb4a41eed73cc867f8e59a))
* **deps:** bump com.nimbusds:nimbus-jose-jwt from 9.40 to 10.9 ([#111](https://github.com/xiaocaishen-michael/my-beloved-server/issues/111)) ([716692d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/716692dc6234efbfee8cf2bbd0c668e3e934f52e))
* **deps:** bump com.squareup.okhttp3:okhttp from 4.12.0 to 5.3.2 ([#113](https://github.com/xiaocaishen-michael/my-beloved-server/issues/113)) ([d77ac6d](https://github.com/xiaocaishen-michael/my-beloved-server/commit/d77ac6d11b8a9118b4750b65675e3a8fedcaf250))
* **deps:** bump io.github.resilience4j:resilience4j-retry ([#116](https://github.com/xiaocaishen-michael/my-beloved-server/issues/116)) ([d2de26f](https://github.com/xiaocaishen-michael/my-beloved-server/commit/d2de26f9000f379f300e4737ec3df36cb4779855))
* **deps:** bump net.logstash.logback:logstash-logback-encoder ([#117](https://github.com/xiaocaishen-michael/my-beloved-server/issues/117)) ([31674bc](https://github.com/xiaocaishen-michael/my-beloved-server/commit/31674bc5b0aabfe8e6a41db4eccaf471f1fb4ba4))
* **deps:** bump net.logstash.logback:logstash-logback-encoder ([#122](https://github.com/xiaocaishen-michael/my-beloved-server/issues/122)) ([8d68452](https://github.com/xiaocaishen-michael/my-beloved-server/commit/8d68452df37ed2f069c293595e680b7b2c128cfd))
* **deps:** bump org.apache.maven.plugins:maven-enforcer-plugin ([#112](https://github.com/xiaocaishen-michael/my-beloved-server/issues/112)) ([2bbec3c](https://github.com/xiaocaishen-michael/my-beloved-server/commit/2bbec3cebd3f3e88f8d49d6af726d964713245c1))
* **deps:** bump pgjdbc 42.7.10 → 42.7.11 (CVE-2026-42198) ([#130](https://github.com/xiaocaishen-michael/my-beloved-server/issues/130)) ([d4c81b7](https://github.com/xiaocaishen-michael/my-beloved-server/commit/d4c81b7d12a9f5eb41966a80087e7efcd7b0a02f))
* **deps:** bump the github-actions group across 1 directory with 7 updates ([#115](https://github.com/xiaocaishen-michael/my-beloved-server/issues/115)) ([20590ed](https://github.com/xiaocaishen-michael/my-beloved-server/commit/20590edcd6c542dc521ef4f54706af88216edeaa))
* **deps:** bump the lint-tools group across 1 directory with 2 updates ([#108](https://github.com/xiaocaishen-michael/my-beloved-server/issues/108)) ([bd6afb3](https://github.com/xiaocaishen-michael/my-beloved-server/commit/bd6afb303ddf9272b06665f4fe756782789f24ad))
* **deps:** pin logstash-logback-encoder to 8.x (Jackson 3.x deferred) ([#121](https://github.com/xiaocaishen-michael/my-beloved-server/issues/121)) ([43c3748](https://github.com/xiaocaishen-michael/my-beloved-server/commit/43c3748fc934cd2a165aa62d5d499cc9e76fbd82))
* **main:** release 0.1.1-SNAPSHOT ([#39](https://github.com/xiaocaishen-michael/my-beloved-server/issues/39)) ([87636b5](https://github.com/xiaocaishen-michael/my-beloved-server/commit/87636b531a1436483d015055b431f2c9320a3285))
* **repo:** add scripts/dev-server.sh + markdownlint pre-commit hook ([#140](https://github.com/xiaocaishen-michael/my-beloved-server/issues/140)) ([0136a50](https://github.com/xiaocaishen-michael/my-beloved-server/commit/0136a50f84b55cb369c80fc0ad58241688ef87db))
* **repo:** disable markdownlint MD032 (per prettier preset consensus) ([#141](https://github.com/xiaocaishen-michael/my-beloved-server/issues/141)) ([5d1a405](https://github.com/xiaocaishen-michael/my-beloved-server/commit/5d1a405d2e32c46c84ea83aa794865dcd1691d60))
* **repo:** unblock dependabot — commitlint ignore + springdoc major pin ([#120](https://github.com/xiaocaishen-michael/my-beloved-server/issues/120)) ([91689cb](https://github.com/xiaocaishen-michael/my-beloved-server/commit/91689cb9a7747a8974d6d771584b7036e6e23c53))

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
