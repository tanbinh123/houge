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

import io.jsonwebtoken.Claims;
import javax.annotation.Nonnull;
import top.yein.tethys.auth.AuthContext;

/**
 * JWS 认证上下文实现.
 *
 * @author KK (kzou227@qq.com)
 */
class JwsAuthContext implements AuthContext {

  private final long uid;
  private final String token;
  private final String originUid;
  private final Claims claims;

  JwsAuthContext(long uid, String token, String originUid, Claims claims) {
    this.uid = uid;
    this.token = token;
    this.originUid = originUid;
    this.claims = claims;
  }

  @Override
  public long uid() {
    return uid;
  }

  @Nonnull
  @Override
  public String originUid() {
    return originUid;
  }

  @Nonnull
  @Override
  public String token() {
    return token;
  }

  @Override
  public boolean isAnonymous() {
    return false;
  }

  @Override
  public String toString() {
    return "JwsAuthContext{"
        + "uid="
        + uid
        + ", originUid='"
        + originUid
        + '\''
        + ", claims="
        + claims
        + '}';
  }
}
