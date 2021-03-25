/*
 * Copyright 2019-2020 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package top.yein.tethys.core.auth;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static top.yein.tethys.core.BizCodes.C3300;
import static top.yein.tethys.core.BizCodes.C3301;
import static top.yein.tethys.core.BizCodes.C3302;
import static top.yein.tethys.core.BizCodes.C3305;
import static top.yein.tethys.core.BizCodes.C3309;
import static top.yein.tethys.core.BizCodes.C401;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import top.yein.chaos.biz.BizCodeException;
import top.yein.tethys.domain.CachedJwtAlgorithm;
import top.yein.tethys.storage.JwtSecretDao;

/**
 * {@link JwsAuthService} 单元测试.
 *
 * @author KK (kzou227@qq.com)
 */
class JwsAuthServiceTest {

  String kid = "test";
  Algorithm algorithm =
      Algorithm.HMAC512(
          "29c5fab077c009b9e6676b2f082a7ab3b0462b41acf75f075b5a7bac5619ec81c9d8bb2e25b6d33800fba279ee492ac7d05220e829464df3ca8e00298c517764");
  Algorithm illegalAlgorithm =
      Algorithm.HMAC512(
          "29c5fab077c009b9e6676b2f082a7ab3b0462b41acf75f075b5a7bac5619ec81c9d8bb2e25b6d33800fba279ee492ac7d05220e829464df3ca8e00298c517764-illegal-secret");

  private CachedJwtAlgorithm cachedJwtAlgorithm;
  private JwtSecretDao jwtSecretDao;

  private JwsAuthService newJwsAuthService() {
    return newJwsAuthService(false);
  }

  private JwsAuthService newJwsAuthService(boolean anonymousEnabled) {
    this.jwtSecretDao = mock(JwtSecretDao.class);
    this.cachedJwtAlgorithm = CachedJwtAlgorithm.builder().id(kid).algorithm(algorithm).build();
    when(jwtSecretDao.loadById(kid)).thenReturn(Mono.just(cachedJwtAlgorithm));
    return new JwsAuthService(jwtSecretDao);
  }

  @Test
  void authorize() {
    var token = JWT.create().withKeyId(kid).withJWTId("0").sign(algorithm);

    JwsAuthService authService = newJwsAuthService();
    var p = authService.authenticate(token);
    StepVerifier.create(p)
        .expectNextMatches(ac -> Objects.equals(0L, ac.uid()) && token.equals(ac.token()))
        .verifyComplete();
  }

  @Test
  void nullToken() {
    JwsAuthService authService = newJwsAuthService();
    var p = authService.authenticate(null);
    StepVerifier.create(p)
        .expectErrorMatches(e -> C401 == ((BizCodeException) e).getBizCode())
        .verify();
  }

  @Test
  void illegalToken() {
    JwsAuthService authService = newJwsAuthService();
    assertThatExceptionOfType(BizCodeException.class)
        .isThrownBy(() -> authService.authenticate("illegal token"))
        .matches(e -> C3300 == e.getBizCode());
  }

  @Test
  void expiredToken() {
    var exp = Instant.now(Clock.systemDefaultZone()).minus(1, ChronoUnit.DAYS);
    var token =
        JWT.create().withKeyId(kid).withJWTId("0").withExpiresAt(Date.from(exp)).sign(algorithm);

    JwsAuthService authService = newJwsAuthService();
    var p = authService.authenticate(token);
    StepVerifier.create(p)
        .expectErrorMatches(e -> C3301 == ((BizCodeException) e).getBizCode())
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void tokenNbf() {
    var nbf = Instant.now(Clock.systemDefaultZone()).plus(1, ChronoUnit.DAYS);
    var token =
        JWT.create().withKeyId(kid).withJWTId("0").withNotBefore(Date.from(nbf)).sign(algorithm);

    JwsAuthService authService = newJwsAuthService();
    var p = authService.authenticate(token);
    StepVerifier.create(p)
        .expectErrorMatches(e -> C3302 == ((BizCodeException) e).getBizCode())
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void notFoundKid() {
    JwsAuthService authService = newJwsAuthService();
    when(jwtSecretDao.loadById("not-found-kid")).thenReturn(Mono.empty());

    var token = JWT.create().withKeyId("not-found-kid").withJWTId("0").sign(algorithm);

    var p = authService.authenticate(token);
    StepVerifier.create(p)
        .expectErrorMatches(e -> C3309 == ((BizCodeException) e).getBizCode())
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void wrongSign() {
    JwsAuthService authService = newJwsAuthService();
    when(jwtSecretDao.loadById("not-found-kid")).thenReturn(Mono.just(cachedJwtAlgorithm));

    var token = JWT.create().withKeyId("not-found-kid").withJWTId("0").sign(illegalAlgorithm);
    var p = authService.authenticate(token);
    StepVerifier.create(p)
        .expectErrorMatches(e -> C3305 == ((BizCodeException) e).getBizCode())
        .verify(Duration.ofSeconds(1));
  }
}
