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
package top.yein.tethys.im.server;

import com.google.common.net.HostAndPort;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.log4j.Log4j2;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRoutes;
import reactor.netty.http.server.WebsocketServerSpec;
import top.yein.tethys.core.Env;
import top.yein.tethys.core.http.HttpServerRoutesWrapper;
import top.yein.tethys.core.http.Interceptors;
import top.yein.tethys.core.http.RoutingService;

/**
 * IM Server.
 *
 * @author KK (kzou227@qq.com)
 */
@Log4j2
public final class ImServer {

  private final String addr;
  private final WebsocketHandler websocketHandler;
  private final Interceptors interceptors;
  private final List<RoutingService> routingServices;

  private DisposableServer disposableServer;

  /**
   * 能被 IoC 容器使用的构造函数.
   *
   * @param addr 服务的 IP 和端口
   * @param websocketHandler WS 处理器
   * @param interceptors 拦截器
   * @param routingServices HTTP 路由服务列表
   */
  @Inject
  public ImServer(
      String addr,
      WebsocketHandler websocketHandler,
      Interceptors interceptors,
      List<RoutingService> routingServices) {
    this.addr = addr;
    this.websocketHandler = websocketHandler;
    this.interceptors = interceptors;
    this.routingServices = routingServices;
  }

  /** 启动 IM 服务. */
  public void start() {
    var hap = HostAndPort.fromString(addr);

    // 注册 HTTP 路由
    var routes = HttpServerRoutes.newRoutes();
    for (RoutingService routingService : routingServices) {
      log.info("更新 Routes [resource={}]", routingService);
      routingService.update(routes, interceptors);
    }

    // IM WebSocket 注册
    routes.ws(
        "/im", websocketHandler::handle, WebsocketServerSpec.builder().handlePing(false).build());

    this.disposableServer =
        HttpServer.create()
            .host(hap.getHost())
            .port(hap.getPort())
            .wiretap(Env.current() != Env.PROD)
            .handle(new HttpServerRoutesWrapper(routes))
            .bindNow();
    log.info("IM Server 启动完成 - {}", hap);
  }

  /** 停止 IM 服务. */
  public void stop() {
    if (disposableServer != null) {
      disposableServer.disposeNow();
    }
    log.info("IM Server 停止完成 - {}", addr);
  }
}
