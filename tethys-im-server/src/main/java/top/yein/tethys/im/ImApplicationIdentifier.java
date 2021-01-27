package top.yein.tethys.im;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;
import top.yein.tethys.ApplicationIdentifier;
import top.yein.tethys.core.Version;
import top.yein.tethys.entity.ServerInstance;
import top.yein.tethys.repository.ServerInstanceRepository;
import top.yein.tethys.util.HostNameUtils;
import top.yein.tethys.util.YeinGid;

/**
 * 应用程序标识符接口.
 *
 * @author KK (kzou227@qq.com)
 */
@Log4j2
public class ImApplicationIdentifier implements ApplicationIdentifier {

  /** 随机生成的 FID 最小值. */
  static final int MIN_FID = 99;
  /** 随机生成的 FID 最大值. */
  static final int MAX_FID = YeinGid.FID_MASK;
  /** 构建 FID 的超时时间（秒）. */
  private static final long MAKE_FID_TIMEOUT = 10;

  // INSTANCE 过期的时间
  private static final long INSTANCE_EXPIRES_IN = Duration.ofHours(1).toSeconds();
  // 健康检查的周期
  private static final Duration CHECK_HEALTH_PERIOD = Duration.ofMinutes(5);

  private final ServerInstanceRepository serverInstanceRepository;
  private int fid;

  /**
   * 构造函数.
   *
   * @param serverInstanceRepository
   */
  public ImApplicationIdentifier(ServerInstanceRepository serverInstanceRepository) {
    this.serverInstanceRepository = serverInstanceRepository;
    this.initFid();
    this.checkHealth();
  }

  @Override
  public String applicationName() {
    return "tethys-im";
  }

  @Override
  public int fid() {
    return this.fid;
  }

  @Override
  public String version() {
    return Version.version();
  }

  // 初始化 Fid
  private void initFid() {
    var ran = new SecureRandom();
    var fidQueue = new ArrayBlockingQueue<Integer>(1);
    var isRun = new AtomicBoolean(true);

    Supplier<Mono<Integer>> makeFidFunc =
        () -> {
          var tempFid = ran.nextInt(MAX_FID) + MIN_FID;
          // fid 是否存在
          var fidExists = new AtomicBoolean(false);
          var insertMono =
              Mono.defer(
                  () -> {
                    // 当 FID 不存在时执行插入操作
                    if (fidExists.get()) {
                      return Mono.empty();
                    }
                    var entity = newServerInstance(tempFid);
                    log.info("新增 ServerInstance: {}", entity);

                    return serverInstanceRepository
                        .insert(entity)
                        .doOnNext(
                            rowsUpdated -> {
                              if (rowsUpdated == 1) {
                                fidQueue.add(fid);
                                isRun.set(false);
                              }
                            });
                  });

          return serverInstanceRepository
              .findById(tempFid)
              .filter(
                  si -> {
                    fidExists.set(true);
                    // 服务实例最后检查时间与当前时间相差超过1小时则视服务实例已销毁
                    var diff = Duration.between(si.getCheckTime(), LocalDateTime.now());
                    return diff.toSeconds() > INSTANCE_EXPIRES_IN;
                  })
              .flatMap(
                  si -> {
                    log.info("发现已过期的服务实例: {}", si);
                    var entity = newServerInstance(tempFid);
                    entity.setVer(si.getVer());
                    log.info("修改 ServerInstance: {}", entity);
                    return serverInstanceRepository.update(entity);
                  })
              .doOnNext(
                  rowsUpdated -> {
                    if (rowsUpdated == 1) {
                      fidQueue.add(tempFid);
                      isRun.set(false);
                    }
                  })
              .switchIfEmpty(insertMono);
        };

    Mono.defer(makeFidFunc)
        .repeat(() -> isRun.get())
        .onErrorContinue(
            throwable -> {
              if (!(throwable instanceof R2dbcDataIntegrityViolationException)) {
                return false;
              }
              var ex = (R2dbcDataIntegrityViolationException) throwable;
              // TODO: 主键冲突 - 继续执行
              return "23505".equals(ex.getSqlState());
            },
            (ex, o) -> {})
        .subscribe();

    try {
      this.fid = fidQueue.poll(MAKE_FID_TIMEOUT, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new IllegalStateException("获取服务实例 ID 失败", e);
    }
  }

  private ServerInstance newServerInstance(int id) {
    InetAddress inetAddress;
    try {
      inetAddress = HostNameUtils.getLocalHostLANAddress();
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }

    var e = new ServerInstance();
    e.setId(id);
    e.setHostName(inetAddress.getHostName());
    e.setHostAddress(inetAddress.getHostAddress());
    e.setOsName(System.getProperty("os.name"));
    e.setOsVersion(System.getProperty("os.version"));
    e.setOsArch(System.getProperty("os.arch"));
    e.setOsUser(System.getProperty("user.name"));
    e.setJavaVmName(System.getProperty("java.vm.name"));
    e.setJavaVmVersion(System.getProperty("java.vm.version"));
    e.setJavaVmVendor(System.getProperty("java.vm.vendor"));
    e.setWorkDir(System.getProperty("user.dir"));
    e.setPid(ProcessHandle.current().pid());
    return e;
  }

  private void checkHealth() {
    serverInstanceRepository
        .updateCheckTime(fid)
        .doOnNext(
            rowsUpdated -> {
              if (rowsUpdated == 1) {
                log.info("健康检查成功 fid: {}", fid);
              } else {
                log.error("健康检查失败 fid: {}, rowsUpdated: {}", rowsUpdated);
              }
            })
        .onErrorContinue((ex, o) -> log.warn("健康检查异常", ex))
        .delaySubscription(CHECK_HEALTH_PERIOD)
        .repeat(() -> true)
        .subscribe();
  }
}
